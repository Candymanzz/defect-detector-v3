#pragma once
#include "reference.h"
#include "settings.h"
#include <opencv2/core.hpp>
#include <string>
#include <vector>

namespace cas {

struct InspectOutcome {
    double areaPercent = 0.0;        // anomalous pixels / ROI pixels * 100
    double anomalyScore = 0.0;       // min(1, areaPercent / kScoreFullScalePercent)
    bool reject = false;
    std::string reason;              // "", "score_over_threshold", "shift_exceeded"
    cv::Point2d shift;               // detected translation, original-frame pixels
    double shiftResponse = 0.0;      // phase-correlation confidence
    std::vector<cv::Rect> zones;     // anomaly zones, original-frame pixels
    double alignMs = 0, diffMs = 0, anomalyMs = 0, totalMs = 0;
    // visuals (only filled when requested)
    cv::Size workSize;
    cv::Rect region;
    cv::Mat diff;                    // CV_8U, region-sized
    cv::Mat kept;                    // CV_8U 0/255, region-sized (accepted anomaly blobs)
    cv::Mat aligned;                 // CV_8U, region-sized
};

// Throws std::invalid_argument when the frame size differs from the reference size.
InspectOutcome inspectFrame(const Reference& ref, const cv::Mat& bgr, const Settings& s, bool keepVisuals);

// Gray-u8 heatmap: anomaly energy gated by the accepted blobs + a soft halo, ROI-masked,
// at most `maxWidth` wide (0 = work resolution).
cv::Mat buildHeatmap(const InspectOutcome& out, const NormPolygon& roi, int maxWidth);

// Full-size (work resolution) gray images for the visuals endpoint.
cv::Mat fullDiff(const InspectOutcome& out);
cv::Mat fullMask(const InspectOutcome& out);
cv::Mat fullAligned(const InspectOutcome& out);

}
