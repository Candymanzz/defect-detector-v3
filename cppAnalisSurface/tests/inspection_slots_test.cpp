#include "core/inspection_slots.h"
#include <atomic>
#include <chrono>
#include <fstream>
#include <iostream>
#include <set>
#include <stdexcept>
#include <thread>
#include <vector>
#ifdef __linux__
#include <pthread.h>
#endif

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

template<class F> void invalid(F test) {
    try { test(); } catch (const std::invalid_argument&) { return; }
    throw std::runtime_error("Invalid CPU configuration accepted");
}

int main() {
    try {
        cas::InspectionSlots inherited(1);
        {
            cas::InspectionSlots::Lease lease(inherited);
            require(lease.cpu() == -1, "Unconfigured affinity must stay inherited");
        }
        for (const auto& text : {"3,", "-1", "1--2", "2-1", "1,1", "999999999999", "1x", "1,,2"})
            invalid([&] { cas::InspectionSlots slots(1, text); });
        invalid([] { cas::InspectionSlots slots(2, "1"); });
#ifdef __linux__
        cpu_set_t original;
        require(pthread_getaffinity_np(pthread_self(), sizeof(original), &original) == 0, "getaffinity failed");
        std::vector<int> cpus;
        std::set<std::string> cores;
        int siblingA = -1, siblingB = -1;
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) if (CPU_ISSET(cpu, &original)) {
            std::ifstream topology("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/topology/thread_siblings_list");
            std::string siblings;
            if (!(topology >> siblings)) continue;
            if (cores.insert(siblings).second) cpus.push_back(cpu);
            else if (siblingB < 0) {
                siblingB = cpu;
                for (int candidate : cpus) {
                    std::ifstream file("/sys/devices/system/cpu/cpu" + std::to_string(candidate) + "/topology/thread_siblings_list");
                    std::string group; file >> group;
                    if (group == siblings) siblingA = candidate;
                }
            }
        }
        require(!cpus.empty(), "No CPU topology found");
        if (cpus.size() > 3) cpus.resize(3);
        std::string list;
        for (int cpu : cpus) list += (list.empty() ? "" : ",") + std::to_string(cpu);
        if (siblingA >= 0 && siblingB >= 0)
            invalid([&] { cas::InspectionSlots slots(2, std::to_string(siblingA) + "," + std::to_string(siblingB)); });
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) if (!CPU_ISSET(cpu, &original)) {
            invalid([&] { cas::InspectionSlots slots(1, std::to_string(cpu)); });
            break;
        }
        cas::InspectionSlots slots(static_cast<int>(cpus.size()), list);
        // Destruction during an exception releases both the slot and the thread's original affinity.
        try {
            cas::InspectionSlots::Lease lease(slots);
            require(sched_getcpu() == lease.cpu(), "Inspection did not migrate to its assigned CPU");
            throw 42;
        } catch (int) {}
        cpu_set_t restored;
        require(pthread_getaffinity_np(pthread_self(), sizeof(restored), &restored) == 0, "getaffinity failed");
        require(CPU_EQUAL(&original, &restored), "Affinity not restored after exception");

        std::atomic<int> inside{0}, maximum{0}, errors{0};
        std::mutex activeMutex;
        std::set<int> activeCpus;
        std::vector<std::thread> threads;
        for (int i = 0; i < 12; ++i) threads.emplace_back([&] {
            try {
                for (int iteration = 0; iteration < 8; ++iteration) {
                    {
                        cas::InspectionSlots::Lease lease(slots);
                        cpu_set_t mask;
                        if (pthread_getaffinity_np(pthread_self(), sizeof(mask), &mask) || CPU_COUNT(&mask) != 1 ||
                            !CPU_ISSET(lease.cpu(), &mask) || sched_getcpu() != lease.cpu()) ++errors;
                        {
                            std::lock_guard<std::mutex> lock(activeMutex);
                            if (!activeCpus.insert(lease.cpu()).second) ++errors;
                            int value = ++inside;
                            maximum.store(std::max(maximum.load(), value));
                        }
                        std::this_thread::sleep_for(std::chrono::milliseconds(2));
                        {
                            std::lock_guard<std::mutex> lock(activeMutex);
                            activeCpus.erase(lease.cpu());
                            --inside;
                        }
                    }
                    cpu_set_t mask;
                    if (pthread_getaffinity_np(pthread_self(), sizeof(mask), &mask) || !CPU_EQUAL(&original, &mask)) ++errors;
                }
            } catch (...) { ++errors; }
        });
        for (auto& thread : threads) thread.join();
        require(errors == 0, "CPU assignment, exclusivity or restoration failed");
        require(maximum == static_cast<int>(cpus.size()), "Slots did not run concurrently");
        std::cout << "96 leases: distinct physical CPUs " << list << ", affinity restored, invalid configurations rejected\n";
#else
        invalid([] { cas::InspectionSlots slots(1, "0"); });
#endif
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
