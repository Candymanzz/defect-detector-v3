#include "settings.h"
#include <algorithm>
#include <fstream>

namespace cas {

namespace {
json axisJson(const CalibrationAxis& a) {
    return {{"p0",{a.p0.x,a.p0.y}},{"p1",{a.p1.x,a.p1.y}},{"length_mm",a.lengthMm}};
}
}

cv::Point2d Settings::mmPerPixel(cv::Size size) const {
    if (size.empty()) size = {1224,1024};
    if (calibration.ready()) return calibration.mmPerPixel(size);
    return {estimatedMmPerPixel.x*1224.0/size.width, estimatedMmPerPixel.y*1024.0/size.height};
}

Settings Settings::withMetricDefaults(int cameraId) const {
    Settings s = *this;
    static const cv::Point2d estimates[] = {{.25,.33},{.28,.32},{.31,.31},{.25,.32},{.27,.34},
                                          {.27,.34},{.25,.33},{.22,.23},{.22,.32},{.28,.33}};
    if (!s.estimatedScaleExplicit && cameraId >= 0 && cameraId < 10) s.estimatedMmPerPixel = estimates[cameraId];
    const auto units = s.mmPerPixel({1224,1024});
    if (!s.horizontalShiftMm) s.horizontalShiftMm = std::min(10.0,s.shiftToleranceX*units.x);
    if (!s.verticalShiftMm) s.verticalShiftMm = std::min(10.0,s.shiftToleranceY*units.y);
    return s;
}

Settings Settings::forFrame(cv::Size size) const {
    Settings s = *this;
    const auto units = mmPerPixel(size);
    if (horizontalShiftMm) s.alignmentLimitX = s.shiftToleranceX = *horizontalShiftMm / units.x;
    if (verticalShiftMm) s.alignmentLimitY = s.shiftToleranceY = *verticalShiftMm / units.y;
    return s;
}

json SeamSettings::toJson() const {
    return {{"enabled", enabled}, {"p0", {p0x, p0y}}, {"p1", {p1x, p1y}}, {"band", band},
            {"tol_mid", tolMid}, {"tol_edge", tolEdge}, {"edge_frac", edgeFrac}, {"min_contrast",minContrast}};
}

json Settings::toJson() const {
    json j = {{"sensitivity", sensitivity}, {"threshold", threshold},
            {"shift_tolerance_x", shiftToleranceX}, {"shift_tolerance_y", shiftToleranceY},
            {"min_blob_area", minBlobArea}, {"inspect_scale", inspectScale}, {"seam", seam.toJson()}};
    j["horizontal_shift_tolerance_mm"] = horizontalShiftMm ? json(*horizontalShiftMm) : json(nullptr);
    j["vertical_shift_tolerance_mm"] = verticalShiftMm ? json(*verticalShiftMm) : json(nullptr);
    j["estimated_mm_per_pixel"] = {estimatedMmPerPixel.x,estimatedMmPerPixel.y};
    j["metric_reference_size"] = {1224,1024};
    j["metric_scale_source"] = calibration.ready() ? "calibrated" : "estimated";
    j["calibration"] = {{"horizontal",axisJson(calibration.horizontal)},{"vertical",axisJson(calibration.vertical)}};
    if (alignmentLimitX) j["alignment_limit_x"] = *alignmentLimitX;
    if (alignmentLimitY) j["alignment_limit_y"] = *alignmentLimitY;
    return j;
}

namespace {

// Reads j[key] into `out` when present; validates the range.
bool readNumber(const json& j, const char* key, double lo, double hi, bool loExclusive, double& out, std::string& err) {
    if (!j.contains(key)) return true;
    const json& v = j[key];
    if (!v.is_number()) { err = std::string(key) + " must be a number"; return false; }
    double d = v.get<double>();
    if (!std::isfinite(d) || d > hi || d < lo || (loExclusive && d == lo)) {
        err = std::string(key) + " out of range";
        return false;
    }
    out = d;
    return true;
}


// Reads a normalised point [x, y] (0..1) into x/y when present.
bool readPoint(const json& j, const char* key, double& x, double& y, std::string& err) {
    if (!j.contains(key)) return true;
    const json& a = j[key];
    if (!a.is_array() || a.size() != 2 || !a[0].is_number() || !a[1].is_number()) {
        err = std::string(key) + " must be [x, y]";
        return false;
    }
    double px = a[0].get<double>(), py = a[1].get<double>();
    if (!std::isfinite(px) || !std::isfinite(py) || px < 0 || px > 1 || py < 0 || py > 1) { err = std::string(key) + " out of range"; return false; }
    x = px;
    y = py;
    return true;
}

bool readCalibrationAxis(const json& j, CalibrationAxis& a, bool horizontal, std::string& err) {
    if (!j.is_object()) {err="calibration axis must be an object";return false;}
    CalibrationAxis next=a;
    if (!readPoint(j,"p0",next.p0.x,next.p0.y,err) || !readPoint(j,"p1",next.p1.x,next.p1.y,err) ||
        !readNumber(j,"length_mm",0,100000,false,next.lengthMm,err)) return false;
    if (next.lengthMm > 0 && (next.lengthMm < .001 || !next.valid(horizontal))) {
        err="calibration measurement needs a positive axis span and length";return false;
    }
    a=next;return true;
}
}

std::string SeamSettings::applyJson(const json& j) {
    if (!j.is_object()) return "seam must be a JSON object";
    SeamSettings t = *this;
    std::string err;
    if (j.contains("enabled")) {
        if (!j["enabled"].is_boolean()) return "seam.enabled must be a boolean";
        t.enabled = j["enabled"].get<bool>();
    }
    if (!readPoint(j, "p0", t.p0x, t.p0y, err) || !readPoint(j, "p1", t.p1x, t.p1y, err)) return "seam." + err;
    if (!readNumber(j, "band", 5, 200, false, t.band, err) || !readNumber(j, "tol_mid", 0.1, 50, false, t.tolMid, err) ||
        !readNumber(j, "tol_edge", 0.1, 50, false, t.tolEdge, err) || !readNumber(j, "edge_frac", 0, 0.5, false, t.edgeFrac, err) ||
        !readNumber(j,"min_contrast",1,100,false,t.minContrast,err))
        return "seam." + err;
    *this = t;
    return "";
}

std::string Settings::applyJson(const json& j) {
    if (!j.is_object()) return "settings must be a JSON object";
    Settings tmp = *this;
    std::string err;
    if (j.contains("shift_tolerance") || j.contains("shift_tolerance_x")) {tmp.horizontalShiftMm.reset();tmp.alignmentLimitX.reset();}
    if (j.contains("shift_tolerance") || j.contains("shift_tolerance_y")) {tmp.verticalShiftMm.reset();tmp.alignmentLimitY.reset();}
    double v = tmp.sensitivity;
    if (!readNumber(j, "sensitivity", 0, 1, false, v, err)) return err;
    tmp.sensitivity = v;
    v = tmp.threshold;
    if (!readNumber(j, "threshold", 0, 1, true, v, err)) return err;
    tmp.threshold = v;
    // Old profiles/clients set both axes. Explicit axis values take precedence.
    if (j.contains("shift_tolerance")) {
        v = tmp.shiftToleranceX;
        if (!readNumber(j, "shift_tolerance", 0, 30, false, v, err)) return err;
        tmp.shiftToleranceX = tmp.shiftToleranceY = v;
    }
    v = tmp.shiftToleranceX;
    if (!readNumber(j, "shift_tolerance_x", 0, 30, false, v, err)) return err;
    tmp.shiftToleranceX = v;
    v = tmp.shiftToleranceY;
    if (!readNumber(j, "shift_tolerance_y", 0, 30, false, v, err)) return err;
    tmp.shiftToleranceY = v;
    for (auto item : {std::make_pair("horizontal_shift_tolerance_mm",&tmp.horizontalShiftMm),
                      std::make_pair("vertical_shift_tolerance_mm",&tmp.verticalShiftMm),
                      std::make_pair("alignment_limit_x",&tmp.alignmentLimitX),
                      std::make_pair("alignment_limit_y",&tmp.alignmentLimitY)}) {
        if (!j.contains(item.first)) continue;
        if (j[item.first].is_null()) item.second->reset();
        else {
            double value=0;
            const double limit=std::string(item.first).find("alignment_")==0 ? 100000.0 : 10.0;
            if (!readNumber(j,item.first,0,limit,false,value,err)) return err;
            *item.second=value;
        }
    }
    if (j.contains("estimated_mm_per_pixel")) {
        const auto& units=j["estimated_mm_per_pixel"];
        if (!units.is_array() || units.size()!=2 || !units[0].is_number() || !units[1].is_number())
            return "estimated_mm_per_pixel must contain two positive scales";
        const double x=units[0].get<double>(),y=units[1].get<double>();
        if (!std::isfinite(x)||!std::isfinite(y)||x<.00001||y<.00001||x>100||y>100)
            return "estimated_mm_per_pixel out of range";
        tmp.estimatedMmPerPixel={x,y};tmp.estimatedScaleExplicit=true;
    }
    if (j.contains("calibration")) {
        const auto& c=j["calibration"];
        if (c.is_null()) tmp.calibration={};
        else {
            if (!c.is_object()) return "calibration must be an object";
            if (c.contains("horizontal") && !readCalibrationAxis(c["horizontal"],tmp.calibration.horizontal,true,err)) return err;
            if (c.contains("vertical") && !readCalibrationAxis(c["vertical"],tmp.calibration.vertical,false,err)) return err;
        }
    }
    v = tmp.minBlobArea;
    if (!readNumber(j, "min_blob_area", 1, 100000, false, v, err)) return err;
    tmp.minBlobArea = static_cast<int>(v);
    v = tmp.inspectScale;
    if (!readNumber(j, "inspect_scale", 0.1, 1, false, v, err)) return err;
    tmp.inspectScale = v;
    if (j.contains("seam")) {
        err = tmp.seam.applyJson(j["seam"]);
        if (!err.empty()) return err;
    }
    *this = tmp;
    return "";
}

SettingsPatch SettingsPatch::fromJson(const json& j) {
    SettingsPatch p;
    if (!j.is_object()) return p;
    Settings probe;   // range validation is shared with applyJson; invalid values are ignored
    auto take = [&](const char* key, auto& dst) {
        if (!j.contains(key) || !j[key].is_number()) return;
        json one;
        one[key] = j[key];
        if (probe.applyJson(one).empty()) dst = static_cast<std::decay_t<decltype(*dst)>>(j[key].get<double>());
    };
    take("sensitivity", p.sensitivity);
    take("threshold", p.threshold);
    take("inspect_scale", p.inspectScale);
    take("shift_tolerance", p.shiftToleranceX);
    p.shiftToleranceY = p.shiftToleranceX;
    take("shift_tolerance_x", p.shiftToleranceX);
    take("shift_tolerance_y", p.shiftToleranceY);
    take("min_blob_area", p.minBlobArea);
    p.metric=json::object();
    for (const char* key : {"horizontal_shift_tolerance_mm","vertical_shift_tolerance_mm","estimated_mm_per_pixel","calibration"}) {
        if (!j.contains(key)) continue;
        Settings candidate;
        if (candidate.applyJson({{key,j[key]}}).empty()) p.metric[key]=j[key];
    }
    if (j.contains("seam") && j["seam"].is_object()) {
        SeamSettings probeSeam;
        if (probeSeam.applyJson(j["seam"]).empty()) p.seam = j["seam"];   // invalid seam override is ignored as a whole
    }
    return p;
}

void SettingsPatch::applyTo(Settings& s) const {
    if (sensitivity) s.sensitivity = *sensitivity;
    if (threshold) s.threshold = *threshold;
    if (inspectScale) s.inspectScale = *inspectScale;
    if (shiftToleranceX) {s.shiftToleranceX = *shiftToleranceX;s.horizontalShiftMm.reset();s.alignmentLimitX.reset();}
    if (shiftToleranceY) {s.shiftToleranceY = *shiftToleranceY;s.verticalShiftMm.reset();s.alignmentLimitY.reset();}
    if (minBlobArea) s.minBlobArea = *minBlobArea;
    if (seam.is_object()) s.seam.applyJson(seam);
    if (metric.is_object()) s.applyJson(metric);
}

SettingsStore::SettingsStore(std::filesystem::path file) : file_(std::move(file)) { reloadIfStale(); }

void SettingsStore::reloadIfStale() {
    std::error_code ec;
    auto t = std::filesystem::last_write_time(file_, ec);
    if (ec) return;
    if (haveStamp_ && t == stamp_) return;
    std::ifstream in(file_);
    json j = json::parse(in, nullptr, false);
    stamp_ = t;
    haveStamp_ = true;
    if (!j.is_object()) return;
    map_.clear();
    for (auto it = j.begin(); it != j.end(); ++it) {
        Settings s;
        if (s.applyJson(it.value()).empty()) map_[it.key()] = s;
    }
}

void SettingsStore::save() {
    json j = json::object();
    for (auto& [k, v] : map_) {
        j[k] = v.toJson();
        // Unconfigured profiles retain their camera-dependent defaults after another profile is saved.
        if (!v.estimatedScaleExplicit) j[k].erase("estimated_mm_per_pixel");
    }
    std::error_code ec;
    std::filesystem::create_directories(file_.parent_path(), ec);
    auto tmp = file_;
    tmp += ".tmp";
    { std::ofstream(tmp) << j.dump(2); }
    std::filesystem::rename(tmp, file_, ec);
    if (ec) { std::ofstream(file_) << j.dump(2); }
    stamp_ = std::filesystem::last_write_time(file_, ec);
    haveStamp_ = !ec;
}

Settings SettingsStore::get(const std::string& profile, int cameraId) {
    std::lock_guard<std::mutex> lk(m_);
    reloadIfStale();
    auto it = map_.find(profile);
    return (it == map_.end() ? Settings{} : it->second).withMetricDefaults(cameraId);
}

bool SettingsStore::has(const std::string& profile) {
    std::lock_guard<std::mutex> lk(m_);
    reloadIfStale();
    return map_.count(profile) > 0;
}

std::string SettingsStore::set(const std::string& profile, const json& body, Settings& out, int cameraId) {
    std::lock_guard<std::mutex> lk(m_);
    reloadIfStale();
    Settings s = (map_.count(profile) ? map_[profile] : Settings{}).withMetricDefaults(cameraId);
    std::string err = s.applyJson(body);
    if (!err.empty()) return err;
    s = s.withMetricDefaults(cameraId);
    map_[profile] = s;
    save();
    out = s;
    return "";
}

void SettingsStore::reset(const std::string& profile) {
    std::lock_guard<std::mutex> lk(m_);
    reloadIfStale();
    if (map_.erase(profile)) save();
}

}
