#pragma once
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>

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

// Compensate using only fully covered source pixels. Fractional boundary pixels
// may already contain background, which cannot be removed by inverse warping.
// Both images use the same restored support and normalised smoothing weights.
inline cv::Mat alignRoiInspectionGray(const cv::Mat& frame, const cv::Mat& reference,
                                     cv::Size workSize, const cv::Mat& nativeMask,
                                     cv::Point2d shift, cv::Mat& referenceGray, bool supportAlreadyInset = false,
                                     bool useSupportWindow = true) {
    cv::Mat inner, movedMask;
    if(supportAlreadyInset) inner=nativeMask;
    else cv::erode(nativeMask, inner, cv::getStructuringElement(cv::MORPH_RECT, {3, 3}),
              {-1, -1}, 1, cv::BORDER_CONSTANT, 0);
    // A very narrow ROI must not turn into two empty images and pass silently.
    const bool hasInterior = cv::countNonZero(inner) != 0;
    if (!hasInterior) inner = nativeMask;
    // A translated ROI contributes only near its support. Keep the resize
    // grid identical to the full image: arbitrary/rounded scales retain the
    // full-frame path. The halo contains all resize and 5x5 blur neighbours.
    cv::Rect window(0,0,frame.cols,frame.rows);
    cv::Rect workWindow(0,0,workSize.width,workSize.height);
    if(useSupportWindow && frame.cols%workSize.width==0 && frame.rows%workSize.height==0) {
        const int stepX=frame.cols/workSize.width,stepY=frame.rows/workSize.height;
        const auto bounds=cv::boundingRect(inner);
        if(!bounds.empty()) {
            const int left=std::max(0,bounds.x-3*stepX-2)/stepX*stepX;
            const int top=std::max(0,bounds.y-3*stepY-2)/stepY*stepY;
            const int right=std::min(frame.cols,((bounds.br().x+3*stepX+2+stepX-1)/stepX)*stepX);
            const int bottom=std::min(frame.rows,((bounds.br().y+3*stepY+2+stepY-1)/stepY)*stepY);
            window={left,top,right-left,bottom-top};
            workWindow={left/stepX,top/stepY,window.width/stepX,window.height/stepY};
        }
    }
    cv::Rect sourceWindow(0,0,frame.cols,frame.rows);
    if(window!=sourceWindow) {
        const int left=std::clamp(int(std::floor(window.x+shift.x))-2,0,frame.cols);
        const int top=std::clamp(int(std::floor(window.y+shift.y))-2,0,frame.rows);
        const int right=std::clamp(int(std::ceil(window.br().x+shift.x))+2,0,frame.cols);
        const int bottom=std::clamp(int(std::ceil(window.br().y+shift.y))+2,0,frame.rows);
        if(right>left && bottom>top) sourceWindow={left,top,right-left,bottom-top};
    }
    const cv::Mat forward = (cv::Mat_<double>(2, 3) << 1, 0, shift.x-sourceWindow.x, 0, 1, shift.y-sourceWindow.y);
    cv::warpAffine(inner, movedMask, forward, sourceWindow.size(),
        hasInterior ? cv::INTER_LINEAR : cv::INTER_NEAREST, cv::BORDER_CONSTANT, 0);
    cv::compare(movedMask, 255, movedMask, cv::CMP_EQ);
    cv::Mat gray, source, support;
    if (frame.channels() == 1) gray = frame(sourceWindow);
    else cv::cvtColor(frame(sourceWindow), gray, cv::COLOR_BGR2GRAY);
    gray.convertTo(source, CV_32F);
    source.setTo(0, movedMask == 0);
    movedMask.convertTo(support, CV_32F, 1.0 / 255);
    const cv::Mat inverse = (cv::Mat_<double>(2, 3) << 1, 0, sourceWindow.x-shift.x-window.x, 0, 1, sourceWindow.y-shift.y-window.y);
    cv::Mat numerator, weights;
    cv::warpAffine(source, numerator, inverse, window.size(), cv::INTER_LINEAR, cv::BORDER_CONSTANT, 0);
    cv::warpAffine(support, weights, inverse, window.size(), cv::INTER_LINEAR, cv::BORDER_CONSTANT, 0);
    numerator.setTo(0, inner(window) == 0);
    weights.setTo(0, inner(window) == 0);
    cv::Mat refGray, refNumerator;
    if (reference.channels() == 1) refGray = reference(window);
    else cv::cvtColor(reference(window), refGray, cv::COLOR_BGR2GRAY);
    refGray.convertTo(refNumerator, CV_32F);
    cv::multiply(refNumerator, weights, refNumerator);
    if (window.size() != workWindow.size()) {
        cv::resize(numerator, numerator, workWindow.size(), 0, 0, cv::INTER_AREA);
        cv::resize(refNumerator, refNumerator, workWindow.size(), 0, 0, cv::INTER_AREA);
        cv::resize(weights, weights, workWindow.size(), 0, 0, cv::INTER_AREA);
    }
    cv::GaussianBlur(numerator, numerator, {5, 5}, 0);
    cv::GaussianBlur(refNumerator, refNumerator, {5, 5}, 0);
    cv::GaussianBlur(weights, weights, {5, 5}, 0);
    weights = cv::max(weights, 1e-6);
    cv::divide(numerator, weights, numerator);
    cv::divide(refNumerator, weights, refNumerator);
    cv::Mat result;
    numerator.convertTo(result, CV_8U);
    cv::Mat preparedReference;
    refNumerator.convertTo(preparedReference, CV_8U);
    if(workWindow!=cv::Rect(0,0,workSize.width,workSize.height)) {
        cv::Mat fullResult=cv::Mat::zeros(workSize,CV_8U),fullReference=cv::Mat::zeros(workSize,CV_8U);
        result.copyTo(fullResult(workWindow));preparedReference.copyTo(fullReference(workWindow));
        result=fullResult;preparedReference=fullReference;
    }
    referenceGray = preparedReference; // Never write through the shared reference cache.
    return result;
}

}
