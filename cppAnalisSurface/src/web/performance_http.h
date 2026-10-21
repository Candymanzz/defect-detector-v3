#pragma once
#include "core/request_timing.h"
#include <httplib.h>

namespace cas {

// Hooks run on the connection's HTTP thread. IDs also work for direct/manual requests without Java.
inline void installPerformanceTracing(httplib::Server& server, const std::string& detectorId) {
    using TraceClock = RequestTiming::Clock;
    server.set_pre_routing_handler([detectorId](const httplib::Request& req, httplib::Response& res) {
        if (requestTiming.active) requestTiming.finish(0, "no previous HTTP completion callback; elapsed unknown", false);
        requestTiming = RequestTiming{};
        if (!PerformanceLog::instance().enabled()) return httplib::Server::HandlerResponse::Unhandled;
        requestTiming.active = true;
        requestTiming.started = TraceClock::now();
        static std::atomic<unsigned long long> sequence{0};
        std::string id = req.get_header_value("X-Request-Id");
        if (id.empty()) id = detectorId + "-" + std::to_string(++sequence);
        requestTiming.context = {{"request_id", id.substr(0, 256)}, {"method", req.method},
            {"path", req.path}, {"request_bytes", req.body.size()}, {"detector_id", detectorId}};
        for (const auto& pair : {std::pair<const char*, const char*>{"X-Cycle-Id", "cycle_id"},
                {"X-Trigger-Sequence", "trigger_sequence"}, {"X-Camera-Id", "camera_id"}, {"X-Frame-Id", "frame_id"}}) {
            auto value = req.get_header_value(pair.first);
            if (!value.empty()) requestTiming.context[pair.second] = value.substr(0, 256);
        }
        res.set_header("X-Request-Id", id.substr(0, 256));
        auto received = requestTiming.context;
        received["event"] = "http_request_received";
        received["boundary"] = "httplib pre-routing, after HTTP request body read";
        PerformanceLog::instance().write(std::move(received));
        return httplib::Server::HandlerResponse::Unhandled;
    });
    server.set_post_routing_handler([](const httplib::Request&, httplib::Response& res) {
        if (!requestTiming.active) return;
        if (requestTiming.responseReady == TraceClock::time_point{}) requestTiming.responseReady = TraceClock::now();
        requestTiming.add("handler_ready_ms", RequestTiming::elapsed(requestTiming.started));
        auto record = requestTiming.context;
        record["event"] = "http_response_prepared";
        record["handler_ms"] = RequestTiming::elapsed(requestTiming.started);
        record["status_code"] = res.status;
        record["ms"] = requestTiming.stages;
        record["boundary"] = "HTTP body read before pre-routing; response not yet written";
        PerformanceLog::instance().write(std::move(record));
    });
    server.set_logger([](const httplib::Request&, const httplib::Response& res) {
        if (!requestTiming.active) return;
        if (requestTiming.responseReady != TraceClock::time_point{})
            requestTiming.add("response_write_ms", RequestTiming::elapsed(requestTiming.responseReady));
        requestTiming.context["response_bytes"] = res.body.size();
        requestTiming.finish(res.status, "httplib logger after response write; not client acknowledgement");
    });
}

}
