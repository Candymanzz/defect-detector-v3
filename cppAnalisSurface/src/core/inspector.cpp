#include "inspector.h"
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <stdexcept>

namespace cas {

namespace {

using Clock = std::chrono::steady_clock;

double msSince(Clock::time_point t) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}

constexpr double kMinShiftResponse = 0.05;   // below this the shift estimate is not trusted

}

InspectOutcome inspectFrame(const Reference& ref, const cv::Mat& bgr, const Settings& s, bool keepVisuals) {
    const auto tStart = Clock::now();
    if (bgr.empty() || bgr.size() != ref.size()) {
        throw std::invalid_argument("frame size " + std::to_string(bgr.cols) + "x" + std::to_string(bgr.rows) +
                                    " differs from the reference size " + std::to_string(ref.size().width) + "x" +
                                    std::to_string(ref.size().height));
    }
    InspectOutcome out;
    auto prep = ref.prepared(s.inspectScale);
    out.workSize = prep->workSize;
    out.region = prep->region;
    const double scale = prep->scale;
    const cv::Rect region = prep->region;

    // ---- current frame at work resolution ----
    cv::Mat cur;
    cv::cvtColor(bgr, cur, cv::COLOR_BGR2GRAY);
    if (scale != 1.0) cv::resize(cur, cur, prep->workSize, 0, 0, cv::INTER_AREA);
    cv::GaussianBlur(cur, cur, cv::Size(5, 5), 0);

    // ---- cheap translation estimate on a thumbnail of the ROI box ----
    const auto tAlign = Clock::now();
    const int tolWork = std::max(0, static_cast<int>(std::lround(s.shiftTolerance * scale)));
    cv::Mat small, smallF;
    cv::resize(cur(region), small, prep->smallSize, 0, 0, cv::INTER_AREA);
    small.convertTo(smallF, CV_32F);
    double response = 0;
    cv::Point2d sh = cv::phaseCorrelate(prep->refSmall, smallF, prep->hann, &response);
    sh.x *= static_cast<double>(region.width) / prep->smallSize.width;
    sh.y *= static_cast<double>(region.height) / prep->smallSize.height;
    out.shiftResponse = response;
    if (response < kMinShiftResponse) sh = cv::Point2d(0, 0);

    const double maxShift = std::max(8.0, 4.0 * tolWork);
    if (std::abs(sh.x) > maxShift || std::abs(sh.y) > maxShift) {
        out.reject = true;
        out.reason = "shift_exceeded";
        sh = cv::Point2d(0, 0);   // a shift this large cannot be compensated reliably
    }
    out.shift = cv::Point2d(sh.x / scale, sh.y / scale);

    cv::Mat curR = cur(region);
    if (std::abs(sh.x) >= 0.15 || std::abs(sh.y) >= 0.15) {
        cv::Mat m = (cv::Mat_<double>(2, 3) << 1, 0, -sh.x, 0, 1, -sh.y);
        cv::Mat warped;
        cv::warpAffine(curR, warped, m, curR.size(), cv::INTER_LINEAR, cv::BORDER_REPLICATE);
        curR = warped;
    }
    out.alignMs = msSince(tAlign);

    // ---- difference map with brightness normalisation and shift-tolerant envelope ----
    const auto tDiff = Clock::now();
    cv::Mat refR = prep->refGray(region);
    cv::Mat maskR = prep->roiMask(region);
    cv::Scalar mr, sr, mc, sc;
    cv::meanStdDev(refR, mr, sr, maskR);
    cv::meanStdDev(curR, mc, sc, maskR);
    const double gain = sc[0] > 1.0 ? std::clamp(sr[0] / sc[0], 0.5, 2.0) : 1.0;
    cv::Mat aligned;
    curR.convertTo(aligned, CV_8U, gain, mr[0] - mc[0] * gain);

    const int k = 2 * tolWork + 1;
    cv::Mat diff;
    if (k > 1) {
        cv::Mat kernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(k, k));
        cv::Mat refMin, refMax, lo, hi;
        cv::erode(refR, refMin, kernel);
        cv::dilate(refR, refMax, kernel);
        cv::subtract(refMin, aligned, lo);
        cv::subtract(aligned, refMax, hi);
        cv::max(lo, hi, diff);
    } else {
        cv::absdiff(aligned, refR, diff);
    }
    diff.setTo(0, ~maskR);
    out.diffMs = msSince(tDiff);

    // ---- sensitivity -> pixel threshold, blobs, score ----
    const auto tAnomaly = Clock::now();
    const int thr = cvRound(80.0 - 75.0 * std::clamp(s.sensitivity, 0.0, 1.0));
    cv::Mat bin;
    cv::threshold(diff, bin, thr, 255, cv::THRESH_BINARY);
    cv::morphologyEx(bin, bin, cv::MORPH_OPEN, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3)));

    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(bin, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
    const double minArea = std::max(1.0, s.minBlobArea * scale * scale);
    cv::Mat kept = cv::Mat::zeros(bin.size(), CV_8U);
    for (size_t i = 0; i < contours.size(); ++i) {
        if (cv::contourArea(contours[i]) < minArea) continue;
        cv::drawContours(kept, contours, static_cast<int>(i), 255, cv::FILLED);
        cv::Rect bb = cv::boundingRect(contours[i]);
        out.zones.emplace_back(cvRound((bb.x + region.x) / scale), cvRound((bb.y + region.y) / scale),
                               cvRound(bb.width / scale), cvRound(bb.height / scale));
    }
    const int flagged = cv::countNonZero(kept);
    out.areaPercent = prep->roiArea > 0 ? 100.0 * flagged / prep->roiArea : 0.0;
    out.anomalyScore = std::min(1.0, out.areaPercent / kScoreFullScalePercent);
    if (!out.reject && out.anomalyScore >= s.threshold) {
        out.reject = true;
        out.reason = "score_over_threshold";
    }
    out.anomalyMs = msSince(tAnomaly);

    if (keepVisuals) {
        out.diff = diff;
        out.kept = kept;
        out.aligned = aligned;
    }
    out.totalMs = msSince(tStart);
    return out;
}

namespace {

cv::Mat placeInCanvas(const InspectOutcome& out, const cv::Mat& regionImg) {
    cv::Mat canvas = cv::Mat::zeros(out.workSize, CV_8U);
    if (!regionImg.empty()) regionImg.copyTo(canvas(out.region));
    return canvas;
}

}

cv::Mat buildHeatmap(const InspectOutcome& out, const NormPolygon& roi, int maxWidth) {
    // anomaly energy: pixel differences, gated by the accepted blobs
    cv::Mat core = cv::Mat::zeros(out.diff.size(), CV_8U);
    out.diff.copyTo(core, out.kept);
    cv::Mat heat = placeInCanvas(out, core);

    // downscale first (the halo is a visual effect, so it is cheaper to blur the small image)
    int haloKernel = 11;
    if (maxWidth > 0 && heat.cols > maxWidth) {
        const double f = static_cast<double>(maxWidth) / heat.cols;
        cv::resize(heat, heat, cv::Size(maxWidth, std::max(1, cvRound(heat.rows * f))), 0, 0, cv::INTER_AREA);
        haloKernel = std::max(3, (cvRound(11 * f) | 1));
    }
    cv::Mat halo;
    cv::GaussianBlur(heat, halo, cv::Size(haloKernel, haloKernel), 0);
    halo.convertTo(halo, CV_8U, 0.65);
    heat = cv::max(heat, halo);
    if (roi.size() >= 3) {   // re-apply the ROI after resizing: INTER_AREA blends border pixels
        std::vector<cv::Point> pts;
        for (auto& q : roi) pts.emplace_back(cvRound(q.x * (heat.cols - 1)), cvRound(q.y * (heat.rows - 1)));
        cv::Mat mask = cv::Mat::zeros(heat.size(), CV_8U);
        cv::fillPoly(mask, std::vector<std::vector<cv::Point>>{pts}, 255);
        cv::Mat masked;
        heat.copyTo(masked, mask);
        heat = masked;
    }
    return heat;
}

cv::Mat fullDiff(const InspectOutcome& out) { return placeInCanvas(out, out.diff); }
cv::Mat fullMask(const InspectOutcome& out) { return placeInCanvas(out, out.kept); }
cv::Mat fullAligned(const InspectOutcome& out) { return placeInCanvas(out, out.aligned); }

}
