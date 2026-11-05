#include "core/inspector.h"
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
int checks = 0;
void require(bool ok, const std::string& message) {
    ++checks;
    if (!ok) throw std::runtime_error(message);
}

cv::Mat fixture(cv::Size size) {
    cv::Mat frame(size, CV_8UC3);
    cv::RNG rng(20261104);
    rng.fill(frame, cv::RNG::UNIFORM, 30, 225);
    cv::putText(frame, "LABEL", {size.width / 5, size.height / 2},
        cv::FONT_HERSHEY_SIMPLEX, 1.3, {240, 240, 240}, 3);
    return frame;
}

void selfComparison() {
    for (const auto size : {cv::Size(384,320), cv::Size(391,325), cv::Size(391,337),
                           cv::Size(612,510), cv::Size(1224,1024)}) {
        const auto frame = fixture(size);
        for (bool useRoi : {false, true}) {
            cas::Reference reference(frame.clone());
            if (useRoi) reference.setRoi({{.08,.12},{.91,.10},{.90,.86},{.12,.88}});
            const auto fingerprint = reference.fingerprint();
            for (int percent = 20; percent <= 100; ++percent) {
                cas::Settings settings = cas::Settings{}.withMetricDefaults(0);
                settings.inspectScale = percent / 100.0;
                settings.horizontalShiftMm = settings.verticalShiftMm = .05;
                for (int repeat = 0; repeat < 2; ++repeat) {
                    const auto out = cas::inspectFrame(reference, frame, settings, true);
                    const auto context = std::to_string(size.width) + "x" + std::to_string(size.height)
                        + " scale=" + std::to_string(percent) + "% roi=" + std::to_string(useRoi)
                        + " shift=" + std::to_string(out.rawShift.x) + "," + std::to_string(out.rawShift.y);
                    require(!out.reject, "identical frame must pass: " + context);
                    require(cv::norm(out.rawShift) < 1e-3, "identical frame must have zero shift: " + context);
                    require(out.anomalyScore == 0 && out.areaPercent == 0 && cv::countNonZero(out.diff) == 0,
                        "identical frame must have zero difference and score: " + context);
                }
            }
            require(reference.fingerprint() == fingerprint, "preparation must preserve source reference pixels");
        }
    }
}

void trueTranslations() {
    const auto frame = fixture({391,337});
    cas::Reference reference(frame.clone());
    reference.setRoi({{.1,.1},{.9,.1},{.9,.9},{.1,.9}});
    for (double scale : {.2,.5,.73,1.}) {
        for (const auto shift : {cv::Point(12,-10), cv::Point(-12,10), cv::Point(12,0), cv::Point(0,12)}) {
            cv::Mat moved;
            const cv::Mat transform = (cv::Mat_<double>(2,3) << 1,0,shift.x,0,1,shift.y);
            cv::warpAffine(frame,moved,transform,frame.size(),cv::INTER_LINEAR,cv::BORDER_REPLICATE);
            cas::Settings settings;
            settings.inspectScale = scale;
            settings.maxAlignmentShiftX = settings.maxAlignmentShiftY = 24;
            const auto allowed = cas::inspectFrame(reference,moved,settings,false);
            const auto context = "scale=" + std::to_string(scale) + " shift="
                + std::to_string(shift.x) + "," + std::to_string(shift.y)
                + " measured=" + std::to_string(allowed.rawShift.x) + "," + std::to_string(allowed.rawShift.y)
                + " response=" + std::to_string(allowed.shiftResponse);
            // A work pixel spans several source pixels when analysis is reduced.
            const double accuracy = std::max(1.0, .5 / scale);
            require(allowed.shiftResponse >= .3 && std::abs(allowed.rawShift.x-shift.x) < accuracy
                    && std::abs(allowed.rawShift.y-shift.y) < accuracy,
                "real translation must still be measured: " + context);
            require(allowed.reason != "shift_exceeded", "translation within the limit must align: " + context);
            settings.maxAlignmentShiftX = settings.maxAlignmentShiftY = 4;
            const auto denied = cas::inspectFrame(reference,moved,settings,false);
            require(denied.reject && denied.reason == "shift_exceeded",
                "translation exceeding the limit must reject: " + context);
        }
    }
}
}

int main() {
    try {
        cv::setNumThreads(1);
        selfComparison();
        trueTranslations();
        std::cout << "shared preprocessing and translations passed: " << checks << " checks\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
