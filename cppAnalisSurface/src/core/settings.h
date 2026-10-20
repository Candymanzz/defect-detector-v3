#pragma once
#include <nlohmann/json.hpp>
#include <filesystem>
#include <map>
#include <mutex>
#include <optional>
#include <string>

namespace cas {

using json = nlohmann::json;

// Score scale: anomaly_score = min(1, flagged-area-percent / kScoreFullScalePercent).
// With the default threshold 0.25 a frame is rejected when ~1.25% of the ROI area is anomalous.
constexpr double kScoreFullScalePercent = 5.0;

// The real, user-facing inspection settings (per analysis profile = camera).
struct Settings {
    double sensitivity = 0.5;     // 0..1, higher = stricter (lower pixel-difference threshold)
    double threshold = 0.25;      // (0..1], reject when anomaly_score >= threshold
    int shiftTolerance = 3;       // px of the original frame, micro-shift tolerance
    int minBlobArea = 20;         // px of the original frame, smaller anomalies are noise
    double inspectScale = 1.0;    // (0..1], frame is downscaled by this factor before analysis

    json toJson() const;
    // Applies the fields present in `j`; returns an error text or an empty string.
    std::string applyJson(const json& j);
};

// Per-request overrides (never persisted).
struct SettingsPatch {
    std::optional<double> sensitivity, threshold, inspectScale;
    std::optional<int> shiftTolerance, minBlobArea;
    static SettingsPatch fromJson(const json& j);   // ignores missing, wrong-typed or out-of-range fields
    void applyTo(Settings& s) const;
};

// Persistent profile -> Settings map (JSON file). Re-reads the file when it changed on disk,
// so several processes sharing one data dir stay consistent.
class SettingsStore {
public:
    explicit SettingsStore(std::filesystem::path file);
    Settings get(const std::string& profile);
    bool has(const std::string& profile);
    std::string set(const std::string& profile, const json& body, Settings& out);   // error text or ""
    void reset(const std::string& profile);

private:
    void reloadIfStale();
    void save();
    std::filesystem::path file_;
    std::filesystem::file_time_type stamp_{};
    bool haveStamp_ = false;
    std::map<std::string, Settings> map_;
    std::mutex m_;
};

}
