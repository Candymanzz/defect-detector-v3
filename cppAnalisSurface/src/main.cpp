// cppAnalisSurface: reference-based surface inspection service (replacement of the Python analisSurface).
// Usage: cpp_analis_surface [--host 127.0.0.1] [--port 8000] [--workers N] [--data-dir DIR]
// The orchestrator appends/replaces "--port N" when it starts a pool of instances.
#include "service/service.h"
#include "web/routes.h"
#include <opencv2/core/utility.hpp>
#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <string>
#include <thread>

int main(int argc, char** argv) {
    std::string host = "127.0.0.1";
    int port = 8000;
    std::string dataDir = "data";
    int workers = 0;   // 0 = auto

    if (const char* env = std::getenv("ANALIS_INSPECT_WORKERS")) {
        try { workers = std::stoi(env); } catch (...) {}
    }
    for (int i = 1; i < argc; ++i) {
        std::string k = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : ""; };
        try {
            if (k == "--host") host = next();
            else if (k == "--port") port = std::stoi(next());
            else if (k == "--workers") workers = std::stoi(next());
            else if (k == "--data-dir") dataDir = next();
        } catch (...) {
            std::cerr << "bad value for " << k << "\n";
            return 2;
        }
    }
    if (workers <= 0) workers = static_cast<int>(std::max(2u, std::thread::hardware_concurrency()));
    workers = std::clamp(workers, 1, 64);

    // Parallelism comes from concurrent requests; OpenCV-internal thread teams would oversubscribe the CPU.
    cv::setNumThreads(1);

    cas::Service service(dataDir, workers);
    httplib::Server svr;
    // One HTTP thread serves one keep-alive connection, so the pool must exceed the number of concurrent
    // client connections; CPU concurrency is bounded separately by the `workers` limiter in the service.
    const size_t httpThreads = static_cast<size_t>(std::max(64, workers * 4));
    svr.new_task_queue = [httpThreads] { return new httplib::ThreadPool(httpThreads); };
    svr.set_payload_max_length(256ull * 1024 * 1024);
    cas::registerRoutes(svr, service);

    std::cout << "cppAnalisSurface detector_id=" << service.detectorId() << " workers=" << workers
              << " data=" << dataDir << " listening on " << host << ":" << port << std::endl;
    if (!svr.listen(host, port)) {
        std::cerr << "cannot listen on " << host << ":" << port << "\n";
        return 1;
    }
    return 0;
}
