#include "core/performance_log.h"
#include "core/request_timing.h"
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>

int main() {
    namespace fs = std::filesystem;
    using cas::PerformanceLog;
    const auto suffix = std::chrono::steady_clock::now().time_since_epoch().count();
    const auto dir = fs::temp_directory_path() / ("performance-test-" + std::to_string(suffix));
    try {
        PerformanceLog logger;
        logger.init(dir);
        std::vector<std::thread> producers;
        for (int camera = 0; camera < 10; ++camera) producers.emplace_back([&, camera] {
            for (int frame = 0; frame < 100; ++frame)
                logger.write({{"event", "sample"}, {"camera_id", camera}, {"frame_id", frame}});
        });
        for (auto& thread : producers) thread.join();
        logger.close();
        int count = 0;
        for (const auto& file : fs::directory_iterator(dir)) {
            std::ifstream in(file.path());
            std::string line;
            while (std::getline(in, line)) {
                auto row = nlohmann::json::parse(line);
                if (!row.contains("ts_utc") || row.value("component", "") != "cpp_detector")
                    throw std::runtime_error("invalid diagnostic record");
                ++count;
            }
        }
        if (count != 1000 || logger.dropped() != 0) throw std::runtime_error("lost records before shutdown drain");

        const auto phasesDir = dir / "phases";
        PerformanceLog::instance().init(phasesDir);
        cas::requestTiming = cas::RequestTiming{};
        cas::requestTiming.active = true;
        cas::requestTiming.started = cas::RequestTiming::Clock::now();
        cas::requestTiming.context = {{"request_id", "test-request"}, {"cycle_id", "test-cycle"}};
        // Nested phases must not be added to their parent a second time.
        cas::requestTiming.add("algorithm_ms", 2.0);
        cas::requestTiming.add("align_ms", 1.0);
        cas::requestTiming.add("diff_ms", 1.0);
        cas::requestTiming.add("visuals_ms", 3.0);
        cas::requestTiming.add("heatmap_render_ms", 1.0);
        cas::requestTiming.add("heatmap_write_ms", 2.0);
        cas::requestTiming.finish(200, "unit test");
        PerformanceLog::instance().close();
        bool found = false;
        for (const auto& file : fs::directory_iterator(phasesDir)) {
            std::ifstream in(file.path());
            std::string line;
            while (std::getline(in, line)) {
                auto row = nlohmann::json::parse(line);
                if (row.value("accounted_ms", -1.0) != 5.0 || row.value("request_id", "") != "test-request")
                    throw std::runtime_error("invalid phase accounting/correlation");
                found = true;
            }
        }
        if (!found) throw std::runtime_error("missing phase record");
        // Remove only files/directories created by this test, without recursive deletion.
        for (const auto& file : fs::directory_iterator(phasesDir)) fs::remove(file.path());
        fs::remove(phasesDir);
        for (const auto& file : fs::directory_iterator(dir)) fs::remove(file.path());
        fs::remove(dir);
        std::cout << "performance logging tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << " (test output: " << dir.string() << ")\n";
        return 1;
    }
}
