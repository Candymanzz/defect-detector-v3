#pragma once
#include <condition_variable>
#include <mutex>
#include <string>
#include <vector>
#ifdef __linux__
#include <sched.h>
#endif

namespace cas {

// A CPU belongs to a computation slot, not to an HTTP connection. This prevents
// simultaneous requests from landing on the same isolated CPU.
class InspectionSlots {
public:
    explicit InspectionSlots(int workers, const std::string& cpuList = "");
    int workers() const { return static_cast<int>(cpus_.size()); }
    const std::vector<int>& cpus() const { return configuredCpus_; }

    class Lease {
    public:
        explicit Lease(InspectionSlots& owner);
        ~Lease();
        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;
        int cpu() const { return owner_.cpus_[index_]; }
    private:
        InspectionSlots& owner_;
        size_t index_ = 0;
#ifdef __linux__
        cpu_set_t previous_{};
        bool pinned_ = false;
#endif
    };

private:
    void release(size_t index);
    std::vector<int> cpus_, configuredCpus_;
    std::vector<bool> busy_;
    std::mutex mutex_;
    std::condition_variable available_;
};
}
