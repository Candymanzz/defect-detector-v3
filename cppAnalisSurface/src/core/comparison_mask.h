#pragma once
#include "reference.h"
#include <opencv2/imgproc.hpp>
#include <cmath>
#include <string>

namespace cas {

constexpr double kMinimumComparisonCoverage = 0.5;
constexpr double kComparisonShiftConfidence = 0.3;
constexpr double kComparisonShiftEpsilon = 1e-4;
// Smaller estimates can be caused by a weak local defect instead of label motion.
// Keep this threshold in native pixels, independent of the inspection scale.
constexpr double kComparisonShiftDeadbandNative = 0.15;

struct ComparisonMask {
    cv::Mat mask; // full work size, read-only baseline or a new per-request allocation
    cv::Point cropNative;
    cv::Size filterGuardWork{0, 0};
    int samplingGuardNative = 0;
    int area = 0;
    double coverage = 1.0;
    bool active = false;
    std::string status;
};

// Geometry is eroded before user holes are applied: holes must not grow with a shift.
// One native pixel protects linear sampling; work-space radii protect Gaussian 5x5
// and the actual comparison envelope. No image is zero-padded by this operation.
inline ComparisonMask comparisonMask(const Prepared& prepared, cv::Point2d shift,
                                     double confidence, cv::Size envelope) {
    ComparisonMask result;
    result.mask = prepared.roiMask;
    result.area = prepared.roiArea;
    if (prepared.alignment->nativeRoiMask.empty()) {
        result.status = "label_roi_required"; return result;
    }
    if (!std::isfinite(confidence) || confidence < kComparisonShiftConfidence) {
        result.status = "unreliable_shift"; return result;
    }
    if (!std::isfinite(shift.x) || !std::isfinite(shift.y)) {
        result.status = "unreliable_shift"; return result;
    }
    if (std::abs(shift.x) < kComparisonShiftEpsilon && std::abs(shift.y) < kComparisonShiftEpsilon) {
        result.status = "zero_shift"; return result;
    }
    if (std::abs(shift.x) < kComparisonShiftDeadbandNative && std::abs(shift.y) < kComparisonShiftDeadbandNative) {
        result.status = "below_sampling_resolution"; return result;
    }
    const auto& native = prepared.alignment->nativeRoiMask;
    result.active = true;
    result.cropNative = {std::abs(shift.x) < kComparisonShiftDeadbandNative ? 0 : int(std::ceil(std::min(std::abs(shift.x), double(native.cols)))),
                         std::abs(shift.y) < kComparisonShiftDeadbandNative ? 0 : int(std::ceil(std::min(std::abs(shift.y), double(native.rows))))};
    result.filterGuardWork = {2 + (envelope.width - 1) / 2, 2 + (envelope.height - 1) / 2};
    result.samplingGuardNative = 1;
    cv::Mat safe;
    if (result.cropNative.x >= native.cols || result.cropNative.y >= native.rows) {
        safe = cv::Mat::zeros(native.size(), CV_8U);
    } else {
        const cv::Size erosion(2 * (result.cropNative.x + 1) + 1, 2 * (result.cropNative.y + 1) + 1);
        cv::erode(native, safe, cv::getStructuringElement(cv::MORPH_RECT, erosion),
                  {-1, -1}, 1, cv::BORDER_CONSTANT, cv::Scalar(0));
        // Use the same transform and interpolation as the source warp, but zero
        // outside the camera: BORDER_REPLICATE does not create valid observations.
        cv::Mat ones(native.size(), CV_32F, cv::Scalar(1)), sampled;
        const cv::Mat transform = (cv::Mat_<double>(2, 3) << 1, 0, -shift.x, 0, 1, -shift.y);
        cv::warpAffine(ones, sampled, transform, native.size(), cv::INTER_LINEAR,
                       cv::BORDER_CONSTANT, cv::Scalar(0));
        safe.setTo(0, sampled < 1.0f - 1e-6f);
    }
    cv::Mat coverage;
    safe.convertTo(coverage, CV_32F, 1.0 / 255.0);
    if (coverage.size() != prepared.workSize)
        cv::resize(coverage, coverage, prepared.workSize, 0, 0, cv::INTER_AREA);
    cv::Mat fullSupport = coverage >= 1.0f - 1e-6f;
    const cv::Size filter(2 * result.filterGuardWork.width + 1, 2 * result.filterGuardWork.height + 1);
    cv::erode(fullSupport, fullSupport, cv::getStructuringElement(cv::MORPH_RECT, filter),
              {-1, -1}, 1, cv::BORDER_CONSTANT, cv::Scalar(0));
    cv::Mat effective;
    cv::bitwise_and(fullSupport, prepared.roiMask, effective);
    result.mask = effective;
    result.area = cv::countNonZero(result.mask);
    result.coverage = prepared.roiArea > 0 ? double(result.area) / prepared.roiArea : 0.0;
    result.status = result.area > 0 && result.coverage >= kMinimumComparisonCoverage
        ? "applied" : "insufficient_comparison_area";
    return result;
}

inline bool comparisonContains(const cv::Mat& mask, cv::Rect region) {
    return mask.empty() || ((region & cv::Rect({}, mask.size())) == region &&
                           cv::countNonZero(mask(region)) == region.area());
}
}
