#pragma once
#include <opencv2/imgproc.hpp>
#include <algorithm>

namespace cas {

inline cv::Size inspectionWorkSize(cv::Size source, double scale) {
    return {std::max(1, cvRound(source.width * scale)),
            std::max(1, cvRound(source.height * scale))};
}

inline cv::Size shiftDftSize(cv::Size thumbnail) {
    // phaseCorrelate's peak centre has a half-pixel offset for odd DFT sizes.
    // An even optimal size keeps identical thumbnails centred at zero shift.
    return {2 * cv::getOptimalDFTSize((thumbnail.width + 1) / 2),
            2 * cv::getOptimalDFTSize((thumbnail.height + 1) / 2)};
}

inline void padShiftInput(cv::Mat& image, cv::Size dftSize) {
    if (image.size() != dftSize)
        cv::copyMakeBorder(image, image, 0, dftSize.height - image.rows,
            0, dftSize.width - image.cols, cv::BORDER_CONSTANT, cv::Scalar::all(0));
}

// Reference and current frame must use the same integer size and interpolation.
// Passing fx/fy in one path and a rounded size in the other changes the pixels.
inline cv::Mat prepareInspectionGray(const cv::Mat& frame, cv::Size workSize) {
    cv::Mat gray;
    if (frame.channels() == 1) gray = frame.clone();
    else cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);
    if (gray.size() != workSize)
        cv::resize(gray, gray, workSize, 0, 0, cv::INTER_AREA);
    cv::GaussianBlur(gray, gray, cv::Size(5, 5), 0);
    return gray;
}

inline cv::Mat prepareRoiWeights(const cv::Mat& nativeMask, cv::Size workSize) {
    cv::Mat weights;
    nativeMask.convertTo(weights, CV_32F, 1.0 / 255);
    if (weights.size() != workSize)
        cv::resize(weights, weights, workSize, 0, 0, cv::INTER_AREA);
    cv::GaussianBlur(weights, weights, {5, 5}, 0);
    return cv::max(weights, 1e-6);
}

// Normalised convolution prevents the surface outside the label ROI from
// bleeding into the inspected edge during resizing and Gaussian smoothing.
// Every pixel inside the ROI still contributes; this does not erode its edge.
inline cv::Mat prepareRoiInspectionGray(const cv::Mat& frame, cv::Size workSize,
                                      const cv::Mat& nativeMask, const cv::Mat& weights) {
    if (nativeMask.empty()) return prepareInspectionGray(frame, workSize);
    cv::Mat gray, numerator;
    if (frame.channels() == 1) gray = frame;
    else cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);
    gray.convertTo(numerator, CV_32F);
    numerator.setTo(0, nativeMask == 0);
    if (gray.size() != workSize)
        cv::resize(numerator, numerator, workSize, 0, 0, cv::INTER_AREA);
    cv::GaussianBlur(numerator, numerator, {5, 5}, 0);
    cv::divide(numerator, weights, numerator);
    cv::Mat result;
    numerator.convertTo(result, CV_8U);
    return result;
}

// Translate foreground intensities and their support together. Sampling the
// original background during a fractional warp would contaminate the ROI edge
// before the ROI-aware smoothing even starts.
inline cv::Mat alignRoiInspectionGray(const cv::Mat& frame, cv::Size workSize,
                                     const cv::Mat& nativeMask, cv::Point2d shift) {
    cv::Mat movedMask;
    const cv::Mat forward = (cv::Mat_<double>(2, 3) << 1, 0, shift.x, 0, 1, shift.y);
    cv::warpAffine(nativeMask, movedMask, forward, frame.size(), cv::INTER_NEAREST, cv::BORDER_CONSTANT, 0);
    cv::Mat gray, source, support;
    if (frame.channels() == 1) gray = frame;
    else cv::cvtColor(frame, gray, cv::COLOR_BGR2GRAY);
    gray.convertTo(source, CV_32F);
    source.setTo(0, movedMask == 0);
    movedMask.convertTo(support, CV_32F, 1.0 / 255);
    const cv::Mat inverse = (cv::Mat_<double>(2, 3) << 1, 0, -shift.x, 0, 1, -shift.y);
    cv::Mat numerator, weights;
    cv::warpAffine(source, numerator, inverse, frame.size(), cv::INTER_LINEAR, cv::BORDER_CONSTANT, 0);
    cv::warpAffine(support, weights, inverse, frame.size(), cv::INTER_LINEAR, cv::BORDER_CONSTANT, 0);
    numerator.setTo(0, nativeMask == 0);
    weights.setTo(0, nativeMask == 0);
    if (frame.size() != workSize) {
        cv::resize(numerator, numerator, workSize, 0, 0, cv::INTER_AREA);
        cv::resize(weights, weights, workSize, 0, 0, cv::INTER_AREA);
    }
    cv::GaussianBlur(numerator, numerator, {5, 5}, 0);
    cv::GaussianBlur(weights, weights, {5, 5}, 0);
    cv::divide(numerator, cv::max(weights, 1e-6), numerator);
    cv::Mat result;
    numerator.convertTo(result, CV_8U);
    return result;
}

}
