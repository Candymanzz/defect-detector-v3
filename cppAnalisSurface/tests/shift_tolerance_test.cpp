#include "core/inspector.h"
#include "core/settings.h"
#include <opencv2/imgproc.hpp>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>

namespace {

int checks = 0;

void require(bool condition, const char* message) {
    ++checks;
    if (!condition) throw std::runtime_error(message);
}

void settingsTests(const std::filesystem::path& dir) {
    cas::Settings s;
    require(s.shiftToleranceX == 3 && s.shiftToleranceY == 3, "default axes must both be 3");
    require(s.applyJson({{"shift_tolerance", 7}}).empty(), "legacy setting must load");
    require(s.shiftToleranceX == 7 && s.shiftToleranceY == 7, "legacy setting must populate both axes");
    require(s.applyJson({{"shift_tolerance_x", 2}}).empty(), "X-only update must load");
    require(s.shiftToleranceX == 2 && s.shiftToleranceY == 7, "X update must preserve Y");
    require(s.applyJson({{"shift_tolerance_y", 0}}).empty(), "zero Y must be accepted");
    require(s.shiftToleranceX == 2 && s.shiftToleranceY == 0, "zero Y must preserve X");
    require(s.applyJson({{"shift_tolerance", 5}, {"shift_tolerance_x", 1}}).empty(), "mixed update must load");
    require(s.shiftToleranceX == 1 && s.shiftToleranceY == 5, "explicit X must override legacy value");
    const auto before = s.toJson();
    for (const auto& invalid : {cas::json(-1), cas::json(31), cas::json("bad"), cas::json(nullptr)}) {
        require(!s.applyJson({{"shift_tolerance_x", 4}, {"shift_tolerance_y", invalid}}).empty(), "invalid axis must be rejected");
        require(s.toJson() == before, "invalid update must be atomic");
    }
    require(!before.contains("shift_tolerance"), "new serialization must use independent axes");
    cas::Settings roundTrip;
    require(roundTrip.applyJson(before).empty() && roundTrip.toJson() == before, "new settings must round-trip");

    cas::SettingsPatch::fromJson({{"shift_tolerance_y", 0}}).applyTo(s);
    require(s.shiftToleranceX == 1 && s.shiftToleranceY == 0, "request override must handle zero without changing X");
    cas::SettingsPatch::fromJson({{"shift_tolerance", 8}, {"shift_tolerance_y", 2}}).applyTo(s);
    require(s.shiftToleranceX == 8 && s.shiftToleranceY == 2, "request axis must override request legacy field");
    cas::SettingsPatch::fromJson({{"shift_tolerance_x", -2}, {"shift_tolerance_y", 30}}).applyTo(s);
    require(s.shiftToleranceX == 8 && s.shiftToleranceY == 30, "invalid request axis must not suppress valid other axis");

    const auto file = dir / "settings.json";
    { std::ofstream out(file); out << cas::json({{"camera", {{"shift_tolerance", 6}, {"threshold", 0.4}}}}).dump(); }
    {
        cas::SettingsStore store(file);
        const auto loaded = store.get("camera");
        require(loaded.shiftToleranceX == 6 && loaded.shiftToleranceY == 6, "old on-disk profile must load into both axes");
        cas::Settings saved;
        require(store.set("camera", {{"shift_tolerance_x", 2}}, saved).empty(), "migrated profile must save");
    }
    cas::SettingsStore restarted(file);
    const auto saved = restarted.get("camera");
    require(saved.shiftToleranceX == 2 && saved.shiftToleranceY == 6 && saved.threshold == 0.4,
            "restart must preserve both axes and other settings");
    std::ifstream in(file);
    const auto disk = cas::json::parse(in);
    require(!disk.at("camera").contains("shift_tolerance") && disk.at("camera").at("shift_tolerance_y") == 6,
            "saved profile must migrate to new keys");
}

cv::Mat fixture() {
    cv::Mat frame(320, 384, CV_8UC3);
    cv::RNG rng(20261026);
    rng.fill(frame, cv::RNG::UNIFORM, 30, 225);
    cv::putText(frame, "LABEL XY", {25, 100}, cv::FONT_HERSHEY_SIMPLEX, 1.3, {250, 250, 250}, 3);
    cv::rectangle(frame, {220, 170, 80, 70}, {10, 10, 10}, 5);
    cv::circle(frame, {90, 230}, 35, {240, 240, 240}, 4);
    return frame;
}

cv::Mat translated(const cv::Mat& image, double dx, double dy) {
    cv::Mat out;
    const cv::Mat matrix = (cv::Mat_<double>(2, 3) << 1, 0, dx, 0, 1, dy);
    cv::warpAffine(image, out, matrix, image.size(),
                   cv::INTER_LINEAR, cv::BORDER_REPLICATE);
    return out;
}

cas::InspectOutcome inspect(const cv::Mat& ref, const cv::Mat& cur, int x, int y, double scale = 1.0) {
    cas::Reference reference(ref.clone());
    cas::Settings s;
    s.shiftToleranceX = x;
    s.shiftToleranceY = y;
    s.inspectScale = scale;
    return cas::inspectFrame(reference, cur, s, true);
}

void inspectionTests() {
    const auto ref = fixture();
    const auto horizontal = translated(ref, 10, 0);
    const auto vertical = translated(ref, 0, 10);
    auto permittedX = inspect(ref, horizontal, 3, 0);
    auto deniedX = inspect(ref, horizontal, 0, 3);
    auto permittedY = inspect(ref, vertical, 0, 3);
    auto deniedY = inspect(ref, vertical, 3, 0);
    require(permittedX.shiftResponse >= 0.3 && std::abs(permittedX.shift.x - 10) < 1, "X translation must be detected");
    require(permittedX.reason != "shift_exceeded" && permittedX.shiftLimit == cv::Point2d(12, 8), "X must use its own compensation limit");
    require(deniedX.reject && deniedX.reason == "shift_exceeded" && deniedX.shift == cv::Point2d(0, 0), "small X limit must reject excessive horizontal shift");
    require(permittedY.shiftResponse >= 0.3 && std::abs(permittedY.shift.y - 10) < 1, "Y translation must be detected");
    require(permittedY.reason != "shift_exceeded" && permittedY.shiftLimit == cv::Point2d(8, 12), "Y must use its own compensation limit");
    require(deniedY.reject && deniedY.reason == "shift_exceeded", "small Y limit must reject excessive vertical shift");
    require(permittedX.envelopeKernel == cv::Size(7, 1) && permittedY.envelopeKernel == cv::Size(1, 7), "envelope width/height must follow X/Y");
    for (const auto delta : {cv::Point(-10, 0), cv::Point(0, -10), cv::Point(10, -10)}) {
        const auto shifted = translated(ref, delta.x, delta.y);
        const auto allowed = inspect(ref, shifted, 3, 3);
        require(allowed.shiftResponse >= 0.3 && allowed.reason != "shift_exceeded"
                && std::abs(allowed.shift.x - delta.x) < 1 && std::abs(allowed.shift.y - delta.y) < 1,
                "negative and combined translations must be compensated");
        const auto denied = inspect(ref, shifted, delta.x ? 0 : 3, delta.y ? 0 : 3);
        require(denied.reject && denied.reason == "shift_exceeded", "axis limits must apply to both signs");
    }

    const auto noTolerance = inspect(ref, ref, 0, 0);
    require(noTolerance.envelopeKernel == cv::Size(1, 1) && noTolerance.shiftLimit == cv::Point2d(8, 8), "zero keeps old positioning minimum but disables local envelope expansion");
    const auto scaled = inspect(ref, ref, 6, 2, 0.5);
    require(scaled.envelopeKernel == cv::Size(7, 3) && scaled.shiftLimit == cv::Point2d(24, 16), "downscale must be applied per axis and limits reported in original pixels");

    auto localShift = ref.clone();
    const cv::Rect patch(110, 120, 90, 100);
    translated(ref, 3, 0)(patch).copyTo(localShift(patch));
    const auto tolerantX = inspect(ref, localShift, 3, 0);
    const auto tolerantY = inspect(ref, localShift, 0, 3);
    require(cv::sum(tolerantX.diff)[0] < cv::sum(tolerantY.diff)[0], "horizontal envelope must better tolerate a local horizontal shift");
    auto localVertical = ref.clone();
    translated(ref, 0, 3)(patch).copyTo(localVertical(patch));
    require(cv::sum(inspect(ref, localVertical, 0, 3).diff)[0] < cv::sum(inspect(ref, localVertical, 3, 0).diff)[0],
            "vertical envelope must better tolerate a local vertical shift");
    for (const auto& outcome : {tolerantX, tolerantY, noTolerance, scaled}) {
        cv::Mat gray, blurred, lo, hi, expected, refMin, refMax;
        cv::cvtColor(ref, gray, cv::COLOR_BGR2GRAY);
        if (outcome.scale != 1.0) cv::resize(gray, gray, outcome.workSize, 0, 0, cv::INTER_AREA);
        cv::GaussianBlur(gray, blurred, {5, 5}, 0);
        const auto kernel = cv::getStructuringElement(cv::MORPH_RECT, outcome.envelopeKernel);
        cv::erode(blurred, refMin, kernel);
        cv::dilate(blurred, refMax, kernel);
        cv::subtract(refMin, outcome.aligned, lo);
        cv::subtract(outcome.aligned, refMax, hi);
        cv::max(lo, hi, expected);
        require(cv::norm(expected, outcome.diff, cv::NORM_INF) == 0, "difference map must match the rectangular X/Y envelope");
    }

    cas::Settings oldSettings, newSettings;
    oldSettings.applyJson({{"shift_tolerance", 4}});
    newSettings.applyJson({{"shift_tolerance_x", 4}, {"shift_tolerance_y", 4}});
    cas::Reference oldReference(ref.clone()), newReference(ref.clone());
    const auto oldOutcome = cas::inspectFrame(oldReference, localShift, oldSettings, true);
    const auto newOutcome = cas::inspectFrame(newReference, localShift, newSettings, true);
    require(cv::norm(oldOutcome.diff, newOutcome.diff, cv::NORM_INF) == 0 && oldOutcome.reject == newOutcome.reject
            && oldOutcome.shift == newOutcome.shift, "legacy and equal-axis inputs must have identical results");
}

}

int main() {
    const auto dir = std::filesystem::temp_directory_path() /
        ("shift-tolerance-test-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try {
        std::filesystem::create_directories(dir);
        cv::setNumThreads(1);
        settingsTests(dir);
        inspectionTests();
        std::filesystem::remove(dir / "settings.json");
        std::filesystem::remove(dir / "settings.json.tmp");
        std::filesystem::remove(dir);
        std::cout << "shift tolerance tests passed: " << checks << " checks\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << " (test output: " << dir.string() << ")\n";
        return 1;
    }
}
