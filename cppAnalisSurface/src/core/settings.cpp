#include "settings.h"
#include <fstream>

namespace cas {

json Settings::toJson() const {
    return {{"sensitivity", sensitivity}, {"threshold", threshold}, {"shift_tolerance", shiftTolerance},
            {"min_blob_area", minBlobArea}, {"inspect_scale", inspectScale}};
}

namespace {

// Reads j[key] into `out` when present; validates the range.
bool readNumber(const json& j, const char* key, double lo, double hi, bool loExclusive, double& out, std::string& err) {
    if (!j.contains(key)) return true;
    const json& v = j[key];
    if (!v.is_number()) { err = std::string(key) + " must be a number"; return false; }
    double d = v.get<double>();
    if (d > hi || d < lo || (loExclusive && d == lo)) {
        err = std::string(key) + " out of range";
        return false;
    }
    out = d;
    return true;
}

}

std::string Settings::applyJson(const json& j) {
    if (!j.is_object()) return "settings must be a JSON object";
    Settings tmp = *this;
    std::string err;
    double v = tmp.sensitivity;
    if (!readNumber(j, "sensitivity", 0, 1, false, v, err)) return err;
    tmp.sensitivity = v;
    v = tmp.threshold;
    if (!readNumber(j, "threshold", 0, 1, true, v, err)) return err;
    tmp.threshold = v;
    v = tmp.shiftTolerance;
    if (!readNumber(j, "shift_tolerance", 0, 30, false, v, err)) return err;
    tmp.shiftTolerance = static_cast<int>(v);
    v = tmp.minBlobArea;
    if (!readNumber(j, "min_blob_area", 1, 100000, false, v, err)) return err;
    tmp.minBlobArea = static_cast<int>(v);
    v = tmp.inspectScale;
    if (!readNumber(j, "inspect_scale", 0.1, 1, false, v, err)) return err;
    tmp.inspectScale = v;
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
    take("shift_tolerance", p.shiftTolerance);
    take("min_blob_area", p.minBlobArea);
    return p;
}

void SettingsPatch::applyTo(Settings& s) const {
    if (sensitivity) s.sensitivity = *sensitivity;
    if (threshold) s.threshold = *threshold;
    if (inspectScale) s.inspectScale = *inspectScale;
    if (shiftTolerance) s.shiftTolerance = *shiftTolerance;
    if (minBlobArea) s.minBlobArea = *minBlobArea;
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
    for (auto& [k, v] : map_) j[k] = v.toJson();
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

Settings SettingsStore::get(const std::string& profile) {
    std::lock_guard<std::mutex> lk(m_);
    reloadIfStale();
    auto it = map_.find(profile);
    return it == map_.end() ? Settings{} : it->second;
}

bool SettingsStore::has(const std::string& profile) {
    std::lock_guard<std::mutex> lk(m_);
    reloadIfStale();
    return map_.count(profile) > 0;
}

std::string SettingsStore::set(const std::string& profile, const json& body, Settings& out) {
    std::lock_guard<std::mutex> lk(m_);
    reloadIfStale();
    Settings s = map_.count(profile) ? map_[profile] : Settings{};
    std::string err = s.applyJson(body);
    if (!err.empty()) return err;
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
