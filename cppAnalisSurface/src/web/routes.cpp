#include "routes.h"
#include "performance_http.h"
#include "core/logger.h"
#include "core/request_timing.h"
#include "core/shm_io.h"
#include "service/service.h"
#include <opencv2/imgcodecs.hpp>
#include <algorithm>
#include <chrono>
#include <iomanip>
#include <iostream>
#include <map>
#include <mutex>
#include <sstream>
#include <vector>

namespace cas {

namespace {

using Clock = std::chrono::steady_clock;

double msSince(Clock::time_point t) { return std::chrono::duration<double, std::milli>(Clock::now() - t).count(); }
double round3(double v) { return std::round(v * 1000.0) / 1000.0; }

// ---------- responses ----------

void sendJson(httplib::Response& res, json body, Service& svc, int status = 200) {
    const auto serializeStarted = Clock::now();
    if (body.is_object() && !body.contains("detector_id")) body["detector_id"] = svc.detectorId();
    res.status = status;
    res.set_content(body.dump(-1, ' ', false, json::error_handler_t::replace), "application/json");
    requestTiming.add("response_serialize_ms", msSince(serializeStarted));
    requestTiming.responseReady = Clock::now();
}

void sendError(httplib::Response& res, Service& svc, int status, const std::string& detail, const std::string& code = "") {
    json j = {{"detail", detail}};
    if (!code.empty()) j["error_code"] = code;
    sendJson(res, j, svc, status);
}

// Runs a handler body, mapping exceptions to the same status codes the Python service used.
template <class F>
void guarded(httplib::Response& res, Service& svc, F&& f) {
    try {
        f();
    } catch (const NoReference& e) {
        Logger::instance().warn(std::string("no_reference: ") + e.what());
        sendError(res, svc, 400, e.what(), "no_reference");
    } catch (const std::invalid_argument& e) {
        Logger::instance().warn(std::string("bad_request: ") + e.what());
        sendError(res, svc, 400, e.what(), "bad_request");
    } catch (const std::runtime_error& e) {
        Logger::instance().warn(std::string("io_error: ") + e.what());
        sendError(res, svc, 400, e.what(), "io_error");   // shared-memory / file problems (was OSError -> 400)
    } catch (const std::exception& e) {
        Logger::instance().error(std::string("internal_error: ") + e.what());
        std::cerr << "[error] " << e.what() << std::endl;
        sendError(res, svc, 500, e.what(), "internal_error");
    }
}

// ---------- request parsing ----------

json parseBody(const httplib::Request& req) {
    TimingPhase timing("request_parse_ms");
    json b = json::parse(req.body, nullptr, false);
    if (!b.is_object()) throw std::invalid_argument("request body must be a JSON object");
    if (requestTiming.active) {
        for (const char* key : {"product_type", "analysis_profile", "cycle_id", "trigger_sequence", "camera_id", "frame_id", "request_id"})
            if (b.contains(key) && b[key].is_primitive()) requestTiming.context[key] = b[key];
    }
    return b;
}

std::string requireString(const json& b, const char* key) {
    if (!b.contains(key) || !b[key].is_string() || b[key].get<std::string>().empty())
        throw std::invalid_argument(std::string("field '") + key + "' is required");
    return b[key].get<std::string>();
}

std::string optString(const json& b, const char* key) {
    return b.contains(key) && b[key].is_string() ? b[key].get<std::string>() : "";
}

NormPolygon parsePoints(const json& arr, const char* what) {
    if (!arr.is_array()) throw std::invalid_argument(std::string(what) + " must be an array of {x,y}");
    NormPolygon pts;
    for (auto& p : arr) {
        if (!p.is_object() || !p.contains("x") || !p.contains("y") || !p["x"].is_number() || !p["y"].is_number())
            throw std::invalid_argument(std::string(what) + " points must contain numeric x and y");
        pts.emplace_back(p["x"].get<double>(), p["y"].get<double>());
    }
    return pts;
}

json pointsJson(const NormPolygon& pts) {
    json a = json::array();
    for (auto& p : pts) a.push_back({{"x", p.x}, {"y", p.y}});
    return a;
}

ShmFrame parseShmFrame(const json& b) {
    ShmFrame f;
    f.name = requireString(b, "shm_name");
    auto num = [&](const char* k, long long def, bool required) -> long long {
        if (!b.contains(k) || b[k].is_null()) {
            if (required) throw std::invalid_argument(std::string("field '") + k + "' is required");
            return def;
        }
        if (!b[k].is_number()) throw std::invalid_argument(std::string("field '") + k + "' must be a number");
        return static_cast<long long>(b[k].get<double>());
    };
    f.width = static_cast<int>(num("width", 0, true));
    f.height = static_cast<int>(num("height", 0, true));
    f.stride = static_cast<int>(num("stride", 0, false));
    f.offset = num("shm_offset", 0, false);
    return f;
}

InspectJob parseJob(const json& b) {
    InspectJob job;
    job.product = requireString(b, "product_type");
    job.profile = optString(b, "analysis_profile");
    // flat overrides (threshold, sensitivity, ...) and an optional nested "settings" object
    job.patch = SettingsPatch::fromJson(b);
    if (b.contains("settings") && b["settings"].is_object()) job.patch = SettingsPatch::fromJson(b["settings"]);
    if (b.contains("roi_polygon_norm") && b["roi_polygon_norm"].is_array())
        job.roi = parsePoints(b["roi_polygon_norm"], "roi_polygon_norm");
    return job;
}

VisualOutputs parseOutputs(const json& b) {
    VisualOutputs o;
    o.heatmapPath = optString(b, "heatmap_u8_output_path");
    o.alignedPath = optString(b, "aligned_image_u8_output_path");
    o.diffPath = optString(b, "diff_map_u8_output_path");
    o.maskPath = optString(b, "segmentation_mask_u8_output_path");
    if (b.contains("heatmap_max_width") && b["heatmap_max_width"].is_number())
        o.heatmapMaxWidth = std::max(0, b["heatmap_max_width"].get<int>());
    return o;
}

// ---------- response bodies ----------

json imageJson(const std::optional<ImageInfo>& i) {
    if (!i) return nullptr;
    return {{"path", i->path}, {"width", i->width}, {"height", i->height},
            {"stride", i->stride}, {"channels", i->channels}, {"dtype", "uint8"}};
}

// Same field set as the Python InspectResponse (unsupported features report empty/zero), plus native fields.
json inspectJson(const std::string& product, const Inspected& r, const Service::Written* w, double totalMs) {
    TimingPhase timing("response_build_ms");
    const auto& o = r.out;
    json j;
    j["product_type"] = product;
    j["status"] = o.reject ? "БРАК" : "ГОДЕН";
    j["anomaly_score"] = round3(o.anomalyScore);
    j["threshold"] = r.settings.threshold;
    j["raw_anomaly_score"] = round3(o.anomalyScore);
    j["rechecked_zones_count"] = 0;
    j["recheck_adjustment"] = 0.0;
    j["rechecked_zone_ids"] = json::array();
    j["main_roi_score"] = round3(o.anomalyScore);
    j["sub_zone_scores"] = json::array();
    j["inspection_id"] = nullptr;
    j["learned_normal_matches_count"] = 0;
    j["learned_normal_adjustment"] = 0.0;
    j["matched_accepted_case_ids"] = json::array();
    j["excluded_normal_zones"] = json::array();
    j["fp_zone_scores"] = json::array();
    j["py_align_ms"] = round3(o.alignMs);
    j["py_diff_ms"] = round3(o.diffMs);
    j["py_anomaly_ms"] = round3(o.anomalyMs);
    j["py_fp_recheck_ms"] = 0.0;
    j["py_heatmap_ms"] = w ? round3(w->heatmapMs) : 0.0;
    j["py_total_ms"] = round3(totalMs);
    // native fields
    j["area_percent"] = round3(o.areaPercent);
    j["reason"] = o.reason;
    j["shift"] = {round3(o.shift.x), round3(o.shift.y)};
    j["shift_raw"] = {round3(o.rawShift.x), round3(o.rawShift.y)};
    j["zones"] = json::array();
    for (auto& z : o.zones) j["zones"].push_back({z.x, z.y, z.width, z.height});
    j["frame_width"] = r.frameSize.width;
    j["frame_height"] = r.frameSize.height;
    j["settings"] = r.settings.toJson();
    j["heatmap_u8"] = w ? imageJson(w->heatmap) : json(nullptr);
    return j;
}

json visualsJson(json base, const Service::Written& w) {
    base["aligned_image_u8"] = imageJson(w.aligned);
    base["diff_map_u8"] = imageJson(w.diff);
    base["segmentation_mask_u8"] = imageJson(w.mask);
    return base;
}

json settingsJson(Service& svc, const std::string& profile) {
    return {{"analysis_profile", profile},
            {"settings", svc.settings().get(profile).toJson()},
            {"defaults", Settings{}.toJson()},
            {"saved", svc.settings().has(profile)}};
}

void logSlow(const char* what, const std::string& product, double ms) {
    TimingPhase timing("legacy_slow_logging_ms");
    if (ms > 400.0) {
        std::cerr << "[slow] " << what << " product=" << product << " ms=" << ms << std::endl;
        Logger::instance().warn(std::string("slow ") + what + " product=" + product + " ms=" + std::to_string(ms));
    }
}

// ---------- inspection log ----------
// Every reject, near-miss (score >= half of the threshold) and slow request is logged in full; calm frames are
// sampled (1 of kOkSampleEvery per product) so ~25 inspections/s do not produce gigabytes per day. A summary
// line per product and minute (counts, score and timing stats) keeps the whole picture in cas-*.log.
constexpr int kOkSampleEvery = 20;

struct ProductStats {
    long long n = 0, rejects = 0, shiftExceeded = 0, sinceLogged = 0;
    Clock::time_point lastFull = Clock::now();
    double scoreSum = 0, scoreMax = 0, msSum = 0, msMax = 0, waitMax = 0;
    std::vector<double> ms;
};

std::mutex g_statsM;
std::map<std::string, ProductStats> g_stats;
Clock::time_point g_lastSummary = Clock::now();

// "<product>#cam=<id>" -> id (empty when the product is not camera-scoped)
std::string cameraOf(const std::string& product) {
    const auto p = product.find("#cam=");
    return p == std::string::npos ? "" : product.substr(p + 5);
}

void flushSummary(std::map<std::string, ProductStats>& stats) {
    for (auto& [product, st] : stats) {
        if (st.n == 0) continue;
        std::sort(st.ms.begin(), st.ms.end());
        const double p95 = st.ms.empty() ? 0.0 : st.ms[std::min(st.ms.size() - 1, static_cast<size_t>(st.ms.size() * 0.95))];
        std::ostringstream os;
        os << std::fixed << std::setprecision(3) << "summary 1min product=" << product << " n=" << st.n
           << " rejects=" << st.rejects << " shift_exceeded=" << st.shiftExceeded << " score_avg=" << st.scoreSum / st.n
           << " score_max=" << st.scoreMax << std::setprecision(1) << " ms_avg=" << st.msSum / st.n << " ms_p95=" << p95
           << " ms_max=" << st.msMax << " wait_max=" << st.waitMax;
        Logger::instance().info(os.str());
    }
    stats.clear();
}

// One JSONL record per (selected) inspection: everything needed to judge/tune the analysis offline.
void logInspection(const char* endpoint, const InspectJob& job, const Inspected& r, double totalMs,
                   const std::string& note = "") {
    TimingPhase timing("legacy_inspection_logging_ms");
    if (requestTiming.active) {
        requestTiming.context["product_type"] = job.product;
        requestTiming.context["analysis_profile"] = job.profile;
        requestTiming.context["verdict"] = r.out.reject ? "reject" : "ok";
        requestTiming.context["reason"] = r.out.reason;
        requestTiming.context["shift_raw"] = {r.out.rawShift.x, r.out.rawShift.y};
        requestTiming.context["shift_response"] = r.out.shiftResponse;
    }
    const auto& o = r.out;
    bool full = o.reject || o.anomalyScore >= 0.5 * r.settings.threshold || totalMs > 400.0 || endpoint != std::string("inspect-shm");
    {
        std::lock_guard<std::mutex> lk(g_statsM);
        auto& st = g_stats[job.product];
        ++st.n;
        st.rejects += o.reject ? 1 : 0;
        st.shiftExceeded += o.reason == "shift_exceeded" ? 1 : 0;
        st.scoreSum += o.anomalyScore;
        st.scoreMax = std::max(st.scoreMax, o.anomalyScore);
        st.msSum += totalMs;
        st.msMax = std::max(st.msMax, totalMs);
        st.waitMax = std::max(st.waitMax, r.waitMs);
        st.ms.push_back(totalMs);
        // calm frames: every kOkSampleEvery-th, or at least one per 30 s so slow cameras still leave a trace
        if (!full && (++st.sinceLogged >= kOkSampleEvery || Clock::now() - st.lastFull > std::chrono::seconds(30))) full = true;
        if (full) { st.sinceLogged = 0; st.lastFull = Clock::now(); }
        if (Clock::now() - g_lastSummary > std::chrono::seconds(60)) {
            g_lastSummary = Clock::now();
            flushSummary(g_stats);
        }
    }
    if (!full) return;

    json j;
    j["endpoint"] = endpoint;
    j["product"] = job.product;
    j["camera"] = cameraOf(job.product);
    j["profile"] = job.profile;
    j["status"] = o.reject ? "reject" : "ok";
    j["reason"] = o.reason;
    j["score"] = round3(o.anomalyScore);
    j["threshold"] = r.settings.threshold;
    j["area_percent"] = round3(o.areaPercent);
    j["settings"] = r.settings.toJson();
    j["frame"] = {r.frameSize.width, r.frameSize.height};
    j["work_size"] = {o.workSize.width, o.workSize.height};
    j["roi_points"] = r.roi.size();
    j["roi_area_px"] = o.roiArea;
    j["scale"] = round3(o.scale);
    j["shift"] = {round3(o.shift.x), round3(o.shift.y)};
    j["shift_response"] = round3(o.shiftResponse);
    j["pixel_threshold"] = o.pixelThreshold;
    j["envelope_kernel"] = o.envelopeKernel;
    j["gain"] = round3(o.gain);
    j["ref_mean"] = round3(o.refMean);
    j["cur_mean"] = round3(o.curMean);
    j["diff_mean"] = round3(o.diffMean);
    j["diff_p99"] = o.diffP99;
    j["diff_max"] = o.diffMax;
    j["blobs_total"] = o.blobsTotal;
    j["blobs_kept"] = o.blobsKept;
    j["largest_dropped_blob"] = round3(o.largestDroppedBlob);
    j["zones"] = json::array();
    for (size_t i = 0; i < o.zones.size(); ++i) {
        const auto& z = o.zones[i];
        j["zones"].push_back({{"rect", {z.x, z.y, z.width, z.height}},
                              {"area", i < o.keptBlobAreas.size() ? round3(o.keptBlobAreas[i]) : 0.0},
                              {"max_diff", i < o.keptBlobMaxDiff.size() ? o.keptBlobMaxDiff[i] : 0}});
    }
    j["ms"] = {{"wait", round3(r.waitMs)}, {"read", round3(r.readMs)}, {"align", round3(o.alignMs)},
               {"diff", round3(o.diffMs)}, {"anomaly", round3(o.anomalyMs)}, {"inspect", round3(o.totalMs)},
               {"request", round3(totalMs)}};
    if (!note.empty()) j["note"] = note;
    Logger::instance().inspection(j);
}

} // namespace

void registerRoutes(httplib::Server& svr, Service& svc) {
    installPerformanceTracing(svr, svc.detectorId());
    svr.Get("/health", [&](const httplib::Request&, httplib::Response& res) {
        sendJson(res, {{"status", "ok"}, {"service", "cppAnalisSurface"}}, svc);
    });
    svr.Get("/detector/health", [&](const httplib::Request&, httplib::Response& res) {
        sendJson(res, {{"status", "ok"}, {"service", "cppAnalisSurface"}, {"workers", svc.workers()}}, svc);
    });

    svr.Post("/clear-inspection-context", [&](const httplib::Request&, httplib::Response& res) {
        guarded(res, svc, [&] {
            json cleared = svc.clearContext();
            Logger::instance().info("context cleared " + cleared.dump());
            sendJson(res, {{"ok", true}, {"cleared", cleared}}, svc);
        });
    });

    // ---- reference ----
    svr.Post("/upload-ref-shm", [&](const httplib::Request& req, httplib::Response& res) {
        guarded(res, svc, [&] {
            json b = parseBody(req);
            std::string product = requireString(b, "product_type");
            ShmFrame f = parseShmFrame(b);
            svc.setReference(product, readBgrFrame(f.name, f.width, f.height, f.stride, f.offset));
            Logger::instance().info("reference uploaded (shm) product=" + product + " size=" +
                                    std::to_string(f.width) + "x" + std::to_string(f.height));
            sendJson(res, {{"message", "Reference uploaded from shared memory"}, {"product_type", product}}, svc);
        });
    });

    svr.Post("/upload-ref", [&](const httplib::Request& req, httplib::Response& res) {
        guarded(res, svc, [&] {
            std::string product = req.form.get_field("product_type");
            if (product.empty() || !req.form.has_file("file")) throw std::invalid_argument("product_type and file are required");
            auto file = req.form.get_file("file");
            std::vector<uchar> bytes(file.content.begin(), file.content.end());
            cv::Mat img = cv::imdecode(bytes, cv::IMREAD_COLOR);
            if (img.empty()) throw std::invalid_argument("cannot decode image");
            svc.setReference(product, img);
            Logger::instance().info("reference uploaded product=" + product + " size=" +
                                    std::to_string(img.cols) + "x" + std::to_string(img.rows));
            sendJson(res, {{"message", "Reference uploaded"}, {"product_type", product}}, svc);
        });
    });

    // ---- ROI (normalized 0..1) ----
    svr.Post("/roi-polygon", [&](const httplib::Request& req, httplib::Response& res) {
        guarded(res, svc, [&] {
            json b = parseBody(req);
            std::string product = requireString(b, "product_type");
            if (!b.contains("points")) throw std::invalid_argument("field 'points' is required");
            NormPolygon pts = parsePoints(b["points"], "points");
            svc.setRoi(product, pts);
            Logger::instance().info("roi set product=" + product + " points=" + std::to_string(pts.size()));   // perspective_line is accepted and ignored (not used by this detector)
            sendJson(res, {{"product_type", product}, {"points", pointsJson(pts)}, {"perspective_line", nullptr}}, svc);
        });
    });

    svr.Get("/roi-polygon/:product", [&](const httplib::Request& req, httplib::Response& res) {
        guarded(res, svc, [&] {
            std::string product = req.path_params.at("product");
            auto roi = svc.roi(product);
            if (!roi) return sendError(res, svc, 404, "ROI polygon not set for this product_type");
            sendJson(res, {{"product_type", product}, {"points", pointsJson(*roi)}, {"perspective_line", nullptr}}, svc);
        });
    });

    // ---- settings (per analysis profile) ----
    svr.Get("/settings/defaults", [&](const httplib::Request&, httplib::Response& res) {
        sendJson(res, {{"settings", Settings{}.toJson()}, {"defaults", Settings{}.toJson()}}, svc);
    });
    svr.Get("/settings/:profile", [&](const httplib::Request& req, httplib::Response& res) {
        sendJson(res, settingsJson(svc, req.path_params.at("profile")), svc);
    });
    svr.Put("/settings/:profile", [&](const httplib::Request& req, httplib::Response& res) {
        guarded(res, svc, [&] {
            std::string profile = req.path_params.at("profile");
            json b = parseBody(req);
            Settings saved;
            std::string err = svc.settings().set(profile, b, saved);
            if (!err.empty()) return sendError(res, svc, 400, err, "bad_settings");
            Logger::instance().info("settings saved profile=" + profile + " " + saved.toJson().dump());
            sendJson(res, settingsJson(svc, profile), svc);
        });
    });
    svr.Delete("/settings/:profile", [&](const httplib::Request& req, httplib::Response& res) {
        std::string profile = req.path_params.at("profile");
        svc.settings().reset(profile);
        Logger::instance().info("settings reset profile=" + profile);
        sendJson(res, settingsJson(svc, profile), svc);
    });

    // ---- inspection ----
    svr.Post("/inspect-shm", [&](const httplib::Request& req, httplib::Response& res) {
        guarded(res, svc, [&] {
            const auto t = Clock::now();
            json b = parseBody(req);
            InspectJob job = parseJob(b);
            VisualOutputs outputs = parseOutputs(b);
            Inspected r = svc.inspectShm(job, parseShmFrame(b), outputs.heatmapPath.size() > 0);
            Service::Written w;
            bool haveVisuals = false;
            if (!outputs.heatmapPath.empty()) {
                try {   // the heatmap is auxiliary: a failed export must never fail the verdict
                    VisualOutputs only;
                    only.heatmapPath = outputs.heatmapPath;
                    only.heatmapMaxWidth = outputs.heatmapMaxWidth;
                    w = svc.writeVisuals(r, only);
                    haveVisuals = true;
                } catch (const std::exception& e) {
                    std::cerr << "[warn] heatmap export failed: " << e.what() << std::endl;
                    Logger::instance().warn(std::string("heatmap export failed: ") + e.what());
                }
            }
            const double ms = msSince(t);
            logSlow("inspect-shm", job.product, ms);
            logInspection("inspect-shm", job, r, ms);
            sendJson(res, inspectJson(job.product, r, haveVisuals ? &w : nullptr, ms), svc);
        });
    });

    svr.Post("/inspect-shm-visuals", [&](const httplib::Request& req, httplib::Response& res) {
        guarded(res, svc, [&] {
            const auto t = Clock::now();
            json b = parseBody(req);
            InspectJob job = parseJob(b);
            VisualOutputs outputs = parseOutputs(b);
            Inspected r = svc.inspectShm(job, parseShmFrame(b), outputs.any());
            Service::Written w;
            try {
                w = svc.writeVisuals(r, outputs);
            } catch (const std::exception& e) {   // this endpoint exists to produce artifacts: no silent loss
                std::cerr << "[error] visual export failed: " << e.what() << std::endl;
                Logger::instance().error(std::string("visual export failed: ") + e.what());
                return sendError(res, svc, 500, std::string("visual output export failed: ") + e.what(), "export_failed");
            }
            logInspection("inspect-shm-visuals", job, r, msSince(t));
            sendJson(res, visualsJson(inspectJson(job.product, r, &w, msSince(t)), w), svc);
        });
    });

    svr.Post("/inspect-test-frame", [&](const httplib::Request& req, httplib::Response& res) {
        guarded(res, svc, [&] {
            const auto t = Clock::now();
            json b = parseBody(req);
            InspectJob job = parseJob(b);
            std::string cacheKey = requireString(b, "cache_key");
            std::string filePath = requireString(b, "file_path");
            VisualOutputs outputs = parseOutputs(b);
            cv::Mat frame = svc.loadTestFrame(cacheKey, filePath, optString(b, "image_url"), job.product);
            Inspected r = svc.inspectMat(job, frame, outputs.any());
            Service::Written w;
            try {
                w = svc.writeVisuals(r, outputs);
            } catch (const std::exception& e) {
                std::cerr << "[warn] test-frame visual export failed: " << e.what() << std::endl;
                Logger::instance().warn(std::string("test-frame visual export failed: ") + e.what());
            }
            logInspection("inspect-test-frame", job, r, msSince(t), filePath);
            sendJson(res, visualsJson(inspectJson(job.product, r, &w, msSince(t)), w), svc);
        });
    });

    // Manual test without shared memory: multipart product_type + file (+ optional settings fields).
    svr.Post("/inspect", [&](const httplib::Request& req, httplib::Response& res) {
        guarded(res, svc, [&] {
            const auto t = Clock::now();
            std::string product = req.form.get_field("product_type");
            if (product.empty() || !req.form.has_file("file")) throw std::invalid_argument("product_type and file are required");
            auto file = req.form.get_file("file");
            std::vector<uchar> bytes(file.content.begin(), file.content.end());
            cv::Mat img = cv::imdecode(bytes, cv::IMREAD_COLOR);
            if (img.empty()) throw std::invalid_argument("cannot decode image");
            InspectJob job;
            job.product = product;
            json overrides = json::object();
            for (const char* k : {"sensitivity", "threshold", "shift_tolerance", "min_blob_area", "inspect_scale"}) {
                std::string v = req.form.get_field(k);
                if (!v.empty()) { try { overrides[k] = std::stod(v); } catch (...) {} }
            }
            job.patch = SettingsPatch::fromJson(overrides);
            Inspected r = svc.inspectMat(job, img, false);
            logInspection("inspect", job, r, msSince(t));
            sendJson(res, inspectJson(product, r, nullptr, msSince(t)), svc);
        });
    });
}

}
