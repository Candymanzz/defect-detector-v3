#include "web/performance_http.h"
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>

int main() {
    namespace fs = std::filesystem;
    const auto dir = fs::temp_directory_path() / ("performance-http-test-" +
        std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try {
        cas::PerformanceLog::instance().init(dir);
        httplib::Server server;
        cas::installPerformanceTracing(server, "test-detector");
        server.Post("/timed", [](const httplib::Request&, httplib::Response& response) {
            { cas::TimingPhase phase("visuals_ms"); std::this_thread::sleep_for(std::chrono::milliseconds(5)); }
            response.set_content("{}", "application/json");
            cas::requestTiming.responseReady = cas::RequestTiming::Clock::now();
        });
        int port = server.bind_to_any_port("127.0.0.1");
        if (port <= 0) throw std::runtime_error("cannot bind loopback test server");
        std::thread http([&] { server.listen_after_bind(); });
        try {
            httplib::Client client("127.0.0.1", port);
            client.set_read_timeout(2, 0);
            auto result = client.Post("/timed", {{"X-Request-Id", "test-request"}, {"X-Cycle-Id", "cycle:7"},
                {"X-Camera-Id", "2"}, {"X-Frame-Id", "123"}}, "{}", "application/json");
            if (!result || result->status != 200 || result->get_header_value("X-Request-Id") != "test-request")
                throw std::runtime_error("HTTP behavior/correlation changed");
            auto missing = client.Get("/missing");
            if (!missing || missing->status != 404) throw std::runtime_error("HTTP error behavior changed");
        } catch (...) { server.stop(); http.join(); throw; }
        server.stop(); http.join();
        cas::PerformanceLog::instance().close();
        int complete = 0;
        bool tracedInspection = false, tracedError = false;
        for (const auto& file : fs::directory_iterator(dir)) {
            std::ifstream input(file.path());
            std::string line;
            while (std::getline(input, line)) {
                auto row = nlohmann::json::parse(line);
                if (row.value("event", "") != "http_request") continue;
                ++complete;
                if (row.value("request_id", "") == "test-request") {
                    if (row.value("cycle_id", "") != "cycle:7" || row.value("camera_id", "") != "2"
                        || row["ms"].value("visuals_ms", 0.0) < 4.0 || row.value("request_ms", 0.0) < 4.0
                        || !row["ms"].contains("response_write_ms")) throw std::runtime_error("incomplete HTTP timing");
                    tracedInspection = true;
                }
                if (row.value("status_code", 0) == 404) tracedError = true;
            }
        }
        if (complete != 2 || !tracedInspection || !tracedError) throw std::runtime_error("missing HTTP diagnostic events");
        for (const auto& file : fs::directory_iterator(dir)) fs::remove(file.path());
        fs::remove(dir);
        std::cout << "performance HTTP tests passed\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
