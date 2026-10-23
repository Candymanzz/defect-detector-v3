#pragma once
#include "line_geometry.h"
#include "settings.h"
#include <filesystem>
#include <map>
#include <mutex>
#include <string>

namespace cas {

// Two independent geometry zones of a camera: the label seam (Ridge: thin bright line) and the label edge (Step:
// light/dark boundary). Control points are normalised 0..1 of the frame; the check itself works in frame pixels.
struct LineZone {
    bool enabled = false;
    int mode = LineParams::Ridge;
    std::vector<cv::Point2d> pts;     // normalised control points
    std::vector<int> skip;            // segments excluded from the check
    int polarity = 0;
    int fitDeg = 0;
    double smoothWin = 60, band = 30, tolMid = 1.0, tolEdge = 5.0, edgeFrac = 0.12, minContrast = 8, maxGap = 1.0;

    json toJson() const;
    // Partial update: applies the fields present in `j`; returns an error text or an empty string.
    std::string applyJson(const json& j);
    LineParams toParams(const cv::Size& frame) const;
    bool usable() const { return enabled && pts.size() >= 2; }
};

struct LinesSettings {
    LinesSettings();
    LineZone seam;   // Ridge
    LineZone edge;   // Step
    json toJson() const;
    std::string applyJson(const json& j);
};

// One zone of an inspection result: verdict numbers plus compact per-sample arrays for the diagram in the UI.
json zoneResultJson(const LineParams& p, const LineResult& r, const cv::Size& frame, int maxSamples = 400);

// Persistent profile -> LinesSettings (JSON file next to settings.json) and the last measured result per profile.
class LinesStore {
public:
    explicit LinesStore(std::filesystem::path file);
    LinesSettings get(const std::string& profile);
    std::string set(const std::string& profile, const json& body, LinesSettings& out);   // error text or ""
    void reset(const std::string& profile);
    void setLast(const std::string& profile, json result);
    json last(const std::string& profile);

private:
    void reloadIfStale();
    void save();
    std::filesystem::path file_;
    std::filesystem::file_time_type stamp_{};
    bool haveStamp_ = false;
    std::map<std::string, LinesSettings> map_;
    std::map<std::string, json> last_;
    std::mutex m_;
};

}
