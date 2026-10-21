#include "service.h"
#include "core/request_timing.h"
#include "core/seam.h"
#include "core/shm_io.h"
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <random>

namespace cas {

namespace fs = std::filesystem;

namespace {

// Seam straightness check on top of the reference comparison. A seam deviation rejects the frame; the reason of the
// reference comparison (if any) stays the primary one. The bounding box of the bad points is added as an anomaly zone.
void applySeam(const cv::Mat& frame, const SeamSettings& seam, InspectOutcome& out) {
    if (!seam.enabled || !seam.hasLine() || out.reason == "size_mismatch") return;
    SeamResult sr = measureSeam(frame, seam);
    out.seamEnabled = true;
    out.seamFound = sr.found;
    out.seamReject = sr.reject;
    out.seamRatio = sr.ratio;
    out.seamMaxDev = sr.maxDev;
    out.seamWorstT = sr.worstT;
    out.seamCoverage = sr.coverage;
    out.seamMs = sr.ms;
    out.totalMs += sr.ms;
    if (!sr.reject) return;
    out.reject = true;
    if (out.reason.empty()) out.reason = "seam_deviation";
    if (!sr.badPts.empty()) {
        cv::Rect bb = cv::boundingRect(sr.badPts);
        bb.x -= 12; bb.y -= 12; bb.width += 24; bb.height += 24;
        bb &= cv::Rect(0, 0, frame.cols, frame.rows);
        if (bb.area() > 0) out.zones.push_back(bb);
    }
}

std::string makeDetectorId() {
    for (const char* env : {"DETECTOR_ID", "PYTHON_APP_ID"}) {
        const char* v = std::getenv(env);
        if (v && *v) return v;
    }
    std::random_device rd;
    std::mt19937_64 gen(rd());
    static const char* hex = "0123456789abcdef";
    std::string id = "cpp-analis-surface-";
    for (int i = 0; i < 12; ++i) id += hex[gen() & 15];
    return id;
}

double msSince(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

}

// Counting limiter: at most `workers` inspections run at the same time.
class Service::Slot {
public:
    explicit Slot(Service& s) : s_(s) {
        std::unique_lock<std::mutex> lk(s_.limitM_);
        s_.limitCv_.wait(lk, [&] { return s_.freeSlots_ > 0; });
        --s_.freeSlots_;
    }
    ~Slot() {
        { std::lock_guard<std::mutex> lk(s_.limitM_); ++s_.freeSlots_; }
        s_.limitCv_.notify_one();
    }
    Slot(const Slot&) = delete;
    Slot& operator=(const Slot&) = delete;

private:
    Service& s_;
};

Service::Service(fs::path dataDir, int workers)
    : detectorId_(makeDetectorId()),
      workers_(std::max(1, workers)),
      settings_(dataDir / "settings.json"),
      freeSlots_(std::max(1, workers)) {}

NormPolygon Service::validated(NormPolygon pts) {
    if (pts.size() < 3) throw std::invalid_argument("ROI polygon must contain at least 3 points");
    for (auto& p : pts) {
        if (!std::isfinite(p.x) || !std::isfinite(p.y)) throw std::invalid_argument("ROI points must be finite numbers");
        if (p.x < -0.001 || p.x > 1.001 || p.y < -0.001 || p.y > 1.001)
            throw std::invalid_argument("ROI points must be normalized to 0..1");
        p.x = std::clamp(p.x, 0.0, 1.0);
        p.y = std::clamp(p.y, 0.0, 1.0);
    }
    return pts;
}

void Service::setReference(const std::string& product, cv::Mat bgr) {
    if (bgr.empty()) throw std::invalid_argument("empty reference image");
    auto ref = std::make_shared<Reference>(std::move(bgr));
    std::lock_guard<std::mutex> lk(regM_);
    auto old = refs_.find(product);
    // a new reference of the same size keeps the camera ROI (the orchestrator may re-send it anyway)
    if (old != refs_.end() && old->second->size() == ref->size()) ref->setRoi(old->second->roi());
    refs_[product] = std::move(ref);
}

std::shared_ptr<Reference> Service::reference(const std::string& product) {
    std::lock_guard<std::mutex> lk(regM_);
    auto it = refs_.find(product);
    return it == refs_.end() ? nullptr : it->second;
}

void Service::setRoi(const std::string& product, NormPolygon pts) {
    auto ref = reference(product);
    if (!ref) throw NoReference(product);
    ref->setRoi(validated(std::move(pts)));
}

std::optional<NormPolygon> Service::roi(const std::string& product) {
    auto ref = reference(product);
    if (!ref) return std::nullopt;
    NormPolygon r = ref->roi();
    if (r.empty()) return std::nullopt;
    return r;
}

nlohmann::json Service::clearContext() {
    std::lock_guard<std::mutex> lk(regM_);
    size_t rois = 0;
    for (auto& [k, v] : refs_) rois += v->roi().empty() ? 0 : 1;
    nlohmann::json cleared = {{"references", refs_.size()}, {"roi_polygons", rois}};
    refs_.clear();
    {
        std::lock_guard<std::mutex> t(testM_);
        testCache_.clear();
    }
    return cleared;
}

Inspected Service::run(const InspectJob& job, const cv::Mat& frame, bool keepVisuals) {
    const auto setupStarted = std::chrono::steady_clock::now();
    auto ref = reference(job.product);
    if (!ref) throw NoReference(job.product);
    if (job.roi) ref->setRoi(validated(*job.roi));

    Inspected r;
    r.settings = settings_.get(job.profile.empty() ? job.product : job.profile);
    job.patch.applyTo(r.settings);
    if (job.seamLine && job.seamLine->size() == 2) {
        const auto& line = *job.seamLine;
        r.settings.seam.p0x = line[0].x; r.settings.seam.p0y = line[0].y;
        r.settings.seam.p1x = line[1].x; r.settings.seam.p1y = line[1].y;
    }
    r.roi = ref->roi();
    r.frameSize = frame.size();
    requestTiming.add("service_setup_ms", msSince(setupStarted));
    r.out = inspectFrame(*ref, frame, r.settings, keepVisuals);
    {
        TimingPhase seamTiming("seam_ms");
        applySeam(frame, r.settings.seam, r.out);
    }
    return r;
}

Inspected Service::inspectShm(const InspectJob& job, const ShmFrame& frame, bool keepVisuals) {
    const auto tWait = std::chrono::steady_clock::now();
    Slot slot(*this);
    const double waitMs = msSince(tWait);
    requestTiming.add("wait_ms", waitMs);
    const auto checkStarted = std::chrono::steady_clock::now();
    if (!reference(job.product)) throw NoReference(job.product);
    requestTiming.add("reference_check_ms", msSince(checkStarted));
    const auto tRead = std::chrono::steady_clock::now();
    cv::Mat bgr = readBgrFrame(frame.name, frame.width, frame.height, frame.stride, frame.offset);
    const double readMs = msSince(tRead);
    requestTiming.add("read_ms", readMs);
    Inspected r = run(job, bgr, keepVisuals);
    r.waitMs = waitMs;
    r.readMs = readMs;
    return r;
}

Inspected Service::inspectMat(const InspectJob& job, const cv::Mat& frame, bool keepVisuals) {
    const auto tWait = std::chrono::steady_clock::now();
    Slot slot(*this);
    const double waitMs = msSince(tWait);
    requestTiming.add("wait_ms", waitMs);
    Inspected r = run(job, frame, keepVisuals);
    r.waitMs = waitMs;
    return r;
}

cv::Mat Service::loadTestFrame(const std::string& cacheKey, const std::string& path, const std::string& imageUrl,
                               const std::string& product) {
    TimingPhase timing("test_frame_load_ms");
    std::error_code ec;
    fs::path p = fs::weakly_canonical(fs::u8path(path), ec);
    if (ec) p = fs::u8path(path);
    auto key = std::make_tuple(cacheKey, p.string(), imageUrl);
    cv::Mat img;
    {
        std::lock_guard<std::mutex> lk(testM_);
        auto it = testCache_.find(key);
        if (it != testCache_.end()) img = it->second;
    }
    if (img.empty()) {
        if (!fs::is_regular_file(p, ec)) throw std::runtime_error("test frame file not found: " + p.string());
        img = readImageFile(p);
        if (img.empty()) throw std::runtime_error("cannot decode test frame: " + p.string());
        std::lock_guard<std::mutex> lk(testM_);
        if (!testCache_.count(key) && testCache_.size() >= 4) testCache_.clear();
        testCache_[key] = img;
    }
    // the test path must match the reference resolution (archive/preview JPEGs can differ)
    if (auto ref = reference(product); ref && img.size() != ref->size())
        cv::resize(img, img, ref->size(), 0, 0, cv::INTER_LINEAR);
    return img;
}

Service::Written Service::writeVisuals(const Inspected& r, const VisualOutputs& o) {
    TimingPhase timing("visuals_ms");
    Written w;
    if (!o.heatmapPath.empty()) {
        const auto t = std::chrono::steady_clock::now();
        cv::Mat heat = buildHeatmap(r.out, r.roi, o.heatmapMaxWidth);
        requestTiming.add("heatmap_render_ms", msSince(t));
        const auto writeStarted = std::chrono::steady_clock::now();
        w.heatmap = writeU8Image(o.heatmapPath, heat);
        requestTiming.add("heatmap_write_ms", msSince(writeStarted));
        w.heatmapMs = msSince(t);
    }
    if (!o.alignedPath.empty()) w.aligned = writeU8Image(o.alignedPath, fullAligned(r.out));
    if (!o.diffPath.empty()) w.diff = writeU8Image(o.diffPath, fullDiff(r.out));
    if (!o.maskPath.empty()) w.mask = writeU8Image(o.maskPath, fullMask(r.out));
    return w;
}

}
