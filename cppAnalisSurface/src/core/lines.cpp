#include "lines.h"
#include <algorithm>
#include <cmath>
#include <fstream>

namespace cas {

LinesSettings::LinesSettings() {
    seam.mode = LineParams::Ridge;
    edge.mode = LineParams::Step;
    edge.polarity = -1;
    edge.band = 10;
    edge.tolMid = edge.tolEdge = 1.5;
    edge.edgeFrac = 0;
    edge.minContrast = 10;
    edge.maxGap = 0.25;
}

json LineZone::toJson() const {
    json a = json::array();
    for (auto& q : pts) a.push_back({std::round(q.x * 1e5) / 1e5, std::round(q.y * 1e5) / 1e5});
    return {{"enabled", enabled}, {"mode", mode == LineParams::Step ? "step" : "ridge"}, {"pts", a}, {"skip", skip},
            {"polarity", polarity}, {"fit_deg", fitDeg}, {"smooth_win", smoothWin}, {"band", band}, {"tol_mid", tolMid},
            {"tol_edge", tolEdge}, {"edge_frac", edgeFrac}, {"min_contrast", minContrast}, {"max_gap", maxGap}};
}

std::string LineZone::applyJson(const json& b) {
    if (!b.is_object()) return "line zone must be a JSON object";
    LineZone t = *this;
    std::string err;
    auto num = [&](const char* k, double lo, double hi, double& dst) {
        if (!b.contains(k)) return true;
        if (!b[k].is_number() || b[k].get<double>() < lo || b[k].get<double>() > hi) {
            err = std::string("bad value for ") + k;
            return false;
        }
        dst = b[k].get<double>();
        return true;
    };
    if (b.contains("enabled")) {
        if (!b["enabled"].is_boolean()) return "bad value for enabled";
        t.enabled = b["enabled"].get<bool>();
    }
    if (b.contains("pts")) {
        const json& a = b["pts"];
        if (!a.is_array() || a.size() > 64) return "pts must be an array of up to 64 points";
        std::vector<cv::Point2d> pts;
        for (auto& p : a) {
            if (!p.is_array() || p.size() != 2 || !p[0].is_number() || !p[1].is_number()) return "bad point in pts";
            const double x = p[0].get<double>(), y = p[1].get<double>();
            if (x < 0 || x > 1 || y < 0 || y > 1) return "pts must be normalised to 0..1";
            pts.emplace_back(x, y);
        }
        t.pts = std::move(pts);
        t.skip.clear();                       // segment numbers refer to the old points
    }
    if (b.contains("skip")) {
        const json& a = b["skip"];
        if (!a.is_array() || a.size() > 64) return "skip must be an array of segment numbers";
        std::vector<int> skip;
        for (auto& v : a) {
            if (!v.is_number_integer() || v.get<int>() < 0 || v.get<int>() > 62) return "bad skip segment";
            skip.push_back(v.get<int>());
        }
        t.skip = std::move(skip);
    }
    double polarity = t.polarity, fitDeg = t.fitDeg;
    if (!num("polarity", -1, 1, polarity) || !num("fit_deg", 0, 6, fitDeg) || !num("band", 5, 200, t.band) ||
        !num("tol_mid", 0.1, 50, t.tolMid) || !num("tol_edge", 0.1, 50, t.tolEdge) || !num("edge_frac", 0, 0.5, t.edgeFrac) ||
        !num("smooth_win", 15, 400, t.smoothWin) || !num("min_contrast", 1, 100, t.minContrast) ||
        !num("max_gap", 0, 1, t.maxGap))
        return err;
    t.polarity = int(polarity);
    t.fitDeg = int(fitDeg);
    *this = std::move(t);
    return "";
}

LineParams LineZone::toParams(const cv::Size& frame) const {
    LineParams p;
    p.enabled = enabled;
    p.mode = mode;
    for (auto& q : pts) p.pts.emplace_back(q.x * (frame.width - 1), q.y * (frame.height - 1));
    p.skip = skip;
    p.polarity = polarity;
    p.fitDeg = fitDeg;
    p.smoothWin = smoothWin;
    p.bandHalf = band;
    p.tolMid = tolMid;
    p.tolEdge = tolEdge;
    p.edgeFrac = edgeFrac;
    p.minContrast = minContrast;
    p.maxGap = maxGap;
    return p;
}

json LinesSettings::toJson() const { return {{"seam", seam.toJson()}, {"edge", edge.toJson()}}; }

std::string LinesSettings::applyJson(const json& j) {
    if (!j.is_object()) return "lines must be a JSON object";
    LinesSettings t = *this;
    if (j.contains("seam")) {
        std::string err = t.seam.applyJson(j["seam"]);
        if (!err.empty()) return "seam: " + err;
    }
    if (j.contains("edge")) {
        std::string err = t.edge.applyJson(j["edge"]);
        if (!err.empty()) return "edge: " + err;
    }
    // the mode of a zone is fixed: the seam is a bright line, the edge is a boundary
    t.seam.mode = LineParams::Ridge;
    t.edge.mode = LineParams::Step;
    *this = std::move(t);
    return "";
}

json zoneResultJson(const LineParams& p, const LineResult& r, const cv::Size& frame, int maxSamples) {
    auto r3 = [](double v) { return std::round(v * 1000.0) / 1000.0; };
    auto r2 = [](double v) { return std::round(v * 100.0) / 100.0; };
    json j = {{"found", r.found}, {"reject", r.reject}, {"gap_reject", r.gapReject}, {"ratio", r2(r.ratio)},
              {"max_dev", r2(r.maxDev)}, {"worst_t", r3(r.worstT)}, {"coverage", r2(r.coverage)},
              {"gap", r3(r.longestGap)}, {"noise", r2(r.noise)}, {"ms", r2(r.ms)}};
    const int N = int(r.rowPos.size());
    if (N < 2) return j;
    const int step = std::max(1, (N + maxSamples - 1) / maxSamples);
    json dev = json::array(), tol = json::array(), edge = json::array(), ref = json::array(), axis = json::array();
    std::string state;
    const double sx = 1.0 / std::max(1, frame.width - 1), sy = 1.0 / std::max(1, frame.height - 1);
    for (int i = 0; i < N; i += step) {
        // the worst sample of the decimated bin stands for it, so a short spike never disappears from the graph
        int k = i;
        for (int m = i; m < std::min(N, i + step); ++m)
            if (r.rowState[m] == 0 && (r.rowState[k] != 0 || std::fabs(r.rowDev[m]) > std::fabs(r.rowDev[k]))) k = m;
        const bool ok = r.rowState[k] == 0;
        state += char('0' + r.rowState[k]);
        dev.push_back(ok ? r2(r.rowDev[k]) : 0.0);
        tol.push_back(r2(r.rowTol[k]));
        edge.push_back({r3(r.rowEdge[k].x * sx), r3(r.rowEdge[k].y * sy)});
        ref.push_back({r3(r.rowRef[k].x * sx), r3(r.rowRef[k].y * sy)});
        axis.push_back({r3(r.rowPos[k].x * sx), r3(r.rowPos[k].y * sy)});
    }
    j["dev"] = std::move(dev);
    j["tol"] = std::move(tol);
    j["state"] = std::move(state);
    j["edge"] = std::move(edge);
    j["ref"] = std::move(ref);
    j["axis"] = std::move(axis);
    j["samples"] = N;
    j["tol_mid"] = p.tolMid;
    j["tol_edge"] = p.tolEdge;
    j["band"] = p.bandHalf;
    return j;
}

LinesStore::LinesStore(std::filesystem::path file) : file_(std::move(file)) { reloadIfStale(); }

void LinesStore::reloadIfStale() {
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
        LinesSettings s;
        if (s.applyJson(it.value()).empty()) map_[it.key()] = s;
    }
}

void LinesStore::save() {
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

LinesSettings LinesStore::get(const std::string& profile) {
    std::lock_guard<std::mutex> lk(m_);
    reloadIfStale();
    auto it = map_.find(profile);
    return it == map_.end() ? LinesSettings{} : it->second;
}

std::string LinesStore::set(const std::string& profile, const json& body, LinesSettings& out) {
    std::lock_guard<std::mutex> lk(m_);
    reloadIfStale();
    LinesSettings s = map_.count(profile) ? map_[profile] : LinesSettings{};
    std::string err = s.applyJson(body);
    if (!err.empty()) return err;
    map_[profile] = s;
    save();
    out = s;
    return "";
}

void LinesStore::reset(const std::string& profile) {
    std::lock_guard<std::mutex> lk(m_);
    reloadIfStale();
    if (map_.erase(profile)) save();
}

void LinesStore::setLast(const std::string& profile, json result) {
    std::lock_guard<std::mutex> lk(m_);
    last_[profile] = std::move(result);
}

json LinesStore::last(const std::string& profile) {
    std::lock_guard<std::mutex> lk(m_);
    auto it = last_.find(profile);
    return it == last_.end() ? json(nullptr) : it->second;
}

}
