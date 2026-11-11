#include "inspection_slots.h"
#include <algorithm>
#include <fstream>
#include <iostream>
#include <set>
#include <stdexcept>
#include <system_error>
#ifdef __linux__
#include <pthread.h>
#endif

namespace cas {
namespace {
std::vector<int> parseCpus(const std::string& text) {
    std::vector<int> cpus;
    size_t pos = 0;
    auto number = [&]() {
        if (pos == text.size() || text[pos] < '0' || text[pos] > '9')
            throw std::invalid_argument("Invalid inspection CPU list: " + text);
        int value = 0;
        do {
            value = value * 10 + (text[pos++] - '0');
            if (value >= 1024) throw std::invalid_argument("Inspection CPU must be below 1024");
        } while (pos < text.size() && text[pos] >= '0' && text[pos] <= '9');
        return value;
    };
    while (pos < text.size()) {
        int first = number(), last = first;
        if (pos < text.size() && text[pos] == '-') { ++pos; last = number(); }
        if (last < first) throw std::invalid_argument("Descending inspection CPU range");
        for (int cpu = first; cpu <= last; ++cpu) {
            if (std::find(cpus.begin(), cpus.end(), cpu) != cpus.end())
                throw std::invalid_argument("Duplicate inspection CPU: " + std::to_string(cpu));
            cpus.push_back(cpu);
        }
        if (pos == text.size()) break;
        if (text[pos++] != ',' || pos == text.size())
            throw std::invalid_argument("Invalid inspection CPU list: " + text);
    }
    return cpus;
}
}

InspectionSlots::InspectionSlots(int workers, const std::string& cpuList) {
    workers = std::max(1, workers);
    if (!cpuList.empty()) {
        configuredCpus_ = parseCpus(cpuList);
        if (static_cast<int>(configuredCpus_.size()) != workers)
            throw std::invalid_argument("Inspection CPU count must equal --workers / ANALIS_INSPECT_WORKERS");
#ifdef __linux__
        cpu_set_t allowed;
        int error = pthread_getaffinity_np(pthread_self(), sizeof(allowed), &allowed);
        if (error) throw std::system_error(error, std::generic_category(), "Read inspection CPU affinity");
        std::set<int> physicalSiblings;
        for (int cpu : configuredCpus_) {
            if (cpu >= CPU_SETSIZE || !CPU_ISSET(cpu, &allowed))
                throw std::invalid_argument("Inspection CPU is outside inherited affinity: " + std::to_string(cpu));
            // Reject two logical threads of the same physical core.
            std::ifstream topology("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/topology/thread_siblings_list");
            std::string siblings;
            if (!(topology >> siblings))
                throw std::runtime_error("Cannot read physical CPU topology for CPU " + std::to_string(cpu));
            if (physicalSiblings.count(cpu))
                throw std::invalid_argument("Inspection CPUs share a physical core: " + std::to_string(cpu));
            for (int sibling : parseCpus(siblings)) physicalSiblings.insert(sibling);
        }
#else
        throw std::invalid_argument("Explicit inspection CPU placement is supported on Linux only");
#endif
    }
    cpus_ = configuredCpus_.empty() ? std::vector<int>(workers, -1) : configuredCpus_;
    busy_.resize(cpus_.size(), false);
}

InspectionSlots::Lease::Lease(InspectionSlots& owner) : owner_(owner) {
    {
        std::unique_lock<std::mutex> lock(owner_.mutex_);
        owner_.available_.wait(lock, [&] {
            return std::find(owner_.busy_.begin(), owner_.busy_.end(), false) != owner_.busy_.end();
        });
        index_ = std::find(owner_.busy_.begin(), owner_.busy_.end(), false) - owner_.busy_.begin();
        owner_.busy_[index_] = true;
    }
    try {
#ifdef __linux__
        if (cpu() >= 0) {
            int error = pthread_getaffinity_np(pthread_self(), sizeof(previous_), &previous_);
            if (error) throw std::system_error(error, std::generic_category(), "Read request CPU affinity");
            cpu_set_t target;
            CPU_ZERO(&target);
            CPU_SET(cpu(), &target);
            error = pthread_setaffinity_np(pthread_self(), sizeof(target), &target);
            if (error) throw std::system_error(error, std::generic_category(), "Pin inspection slot");
            pinned_ = true;
        }
#endif
    } catch (...) {
        owner_.release(index_);
        throw;
    }
}

InspectionSlots::Lease::~Lease() {
#ifdef __linux__
    if (pinned_) {
        int error = pthread_setaffinity_np(pthread_self(), sizeof(previous_), &previous_);
        if (error) std::cerr << "[affinity] Cannot restore request CPU affinity, error=" << error << '\n';
    }
#endif
    owner_.release(index_);
}

void InspectionSlots::release(size_t index) {
    { std::lock_guard<std::mutex> lock(mutex_); busy_[index] = false; }
    available_.notify_one();
}
}
