#include "core/settings.h"
#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>
#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#else
#include <unistd.h>
#endif

namespace {
namespace fs = std::filesystem;
int checks = 0;
void require(bool value, const char* message) {
    ++checks;
    if (!value) throw std::runtime_error(message);
}
std::string contents(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}
template<class F> void expectIoFailure(F&& action) {
    bool failed = false;
    try { action(); }
    catch (const std::runtime_error& error) {
        failed = true;
        require(std::string(error.what()).find("settings") != std::string::npos,
                "persistence error must identify settings");
    }
    require(failed, "failed persistence must not return success");
}
void noTemporaryFiles(const fs::path& directory) {
    for (const auto& entry : fs::directory_iterator(directory))
        require(entry.path().filename().u8string().find(".tmp-") == std::string::npos,
                "owned temporary file leaked");
}

void failedDirectory(const fs::path& root) {
    const auto blocked = root / "parent-is-file";
    { std::ofstream output(blocked); output << "keep this file"; }
    cas::SettingsStore store(blocked / "settings.json");
    cas::Settings output;
    output.threshold = .91;
    expectIoFailure([&] { store.set("camera", {{"threshold", .2}}, output, 0); });
    require(!store.has("camera"), "failed creation added a live profile");
    require(output.threshold == .91, "failed save modified output");
    require(store.get("camera", 0).threshold == .6, "failed save changed default settings");
    require(contents(blocked) == "keep this file", "failed save damaged blocking file");
    cas::SettingsStore restarted(blocked / "settings.json");
    require(!restarted.has("camera"), "failed save appeared after restart");
}

void successfulUpdates(const fs::path& root) {
    const auto file = root / fs::u8path(u8"параметры") / "settings.json";
    cas::SettingsStore store(file), observer(file);
    cas::Settings output;
    const auto patch = cas::json{{"threshold", .2}, {"sensitivity", .73}, {"inspect_scale", .51},
        {"horizontal_shift_tolerance_mm", 1.25}, {"vertical_shift_tolerance_mm", .55},
        {"learning_robust_enabled", false}};
    require(store.set("camera-4", patch, output, 4).empty(), "first save failed");
    require(observer.get("camera-4", 4).toJson() == output.toJson(), "observer did not load committed settings");
    const auto first = output.toJson();
    require(store.set("camera-5", {{"threshold", .8}}, output, 5).empty(), "second camera save failed");
    require(store.get("camera-4", 4).toJson() == first, "another camera changed the first profile");
    cas::SettingsStore restarted(file);
    require(restarted.get("camera-4", 4).toJson() == first, "settings did not survive restart");
    require(restarted.get("camera-5", 5).threshold == .8, "other profile did not survive restart");
    const auto serialized = cas::json::parse(contents(file));
    require(!serialized.at("camera-4").contains("estimated_mm_per_pixel"),
            "saving implicitly estimated scale made it explicit");
    const auto before = contents(file);
    require(!store.set("camera-4", {{"threshold", -1}}, output, 4).empty(), "invalid update accepted");
    require(contents(file) == before, "validation failure wrote settings");
    store.reset("missing-profile");
    require(contents(file) == before, "no-op reset rewrote settings");
    store.reset("camera-4");
    cas::SettingsStore afterReset(file);
    require(!afterReset.has("camera-4") && afterReset.get("camera-5", 5).threshold == .8,
            "reset did not persist or removed another profile");
    // A leftover file from another run must never be opened/truncated as our scratch file.
    auto foreign = file; foreign += ".tmp";
    { std::ofstream sentinel(foreign); sentinel << "foreign temporary file"; }
    require(store.set("camera-5", {{"threshold", .7}}, output, 5).empty(), "replacement failed");
    require(contents(foreign) == "foreign temporary file", "foreign scratch file overwritten");
    noTemporaryFiles(file.parent_path());
}

void failedReplacement(const fs::path& root) {
    const auto directory = root / "replacement";
    const auto file = directory / "settings.json";
    cas::SettingsStore store(file);
    cas::Settings output;
    require(store.set("camera", {{"threshold", .2}}, output, 0).empty(), "baseline save failed");
    const auto before = contents(file);
#ifdef _WIN32
    // Read sharing allows observation but deliberately prevents replacing the target.
    const auto lock = CreateFileW(file.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
    require(lock != INVALID_HANDLE_VALUE, "failed to lock settings target");
    struct Unlock { HANDLE handle; ~Unlock() { CloseHandle(handle); } } unlock{lock};
#else
    // root can bypass these permissions; the Windows lock is the primary local check.
    if (geteuid() == 0) return;
    fs::permissions(directory, fs::perms::owner_read | fs::perms::owner_exec, fs::perm_options::replace);
    struct Unlock { fs::path path; ~Unlock() { std::error_code ec; fs::permissions(path, fs::perms::owner_all, ec); } } unlock{directory};
#endif
    output.threshold = .91;
    expectIoFailure([&] { store.set("camera", {{"threshold", .8}}, output, 0); });
    require(output.threshold == .91, "failed replacement modified output");
    require(store.get("camera", 0).threshold == .2, "failed replacement changed live settings");
    require(contents(file) == before, "failed replacement damaged the previous file");
    expectIoFailure([&] { store.set("new-camera", {{"threshold", .4}}, output, 1); });
    require(!store.has("new-camera"), "failed replacement added a new profile");
    expectIoFailure([&] { store.reset("camera"); });
    require(store.has("camera") && store.get("camera", 0).threshold == .2,
            "failed reset removed the live profile");
    require(contents(file) == before, "failed reset damaged the previous file");
    cas::SettingsStore restarted(file);
    require(restarted.get("camera", 0).threshold == .2, "previous settings lost after failed save/reset");
    noTemporaryFiles(directory);
}

std::string sharedContents(const fs::path& file) {
#ifdef _WIN32
    const auto handle = CreateFileW(file.c_str(), GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, 0, nullptr);
    if (handle == INVALID_HANDLE_VALUE) throw std::runtime_error("reader could not open committed file");
    std::string text;
    char buffer[4096]; DWORD count = 0;
    bool ok;
    do {
        ok = ReadFile(handle, buffer, sizeof(buffer), &count, nullptr) != 0;
        if (ok) text.append(buffer, count);
    } while (ok && count);
    CloseHandle(handle);
    if (!ok) throw std::runtime_error("reader could not read committed file");
    return text;
#else
    return contents(file);
#endif
}

void atomicReaders(const fs::path& root) {
    const auto file = root / "readers" / "settings.json";
    cas::SettingsStore store(file); cas::Settings output;
    require(store.set("camera", {{"threshold", .2}}, output, 0).empty(), "reader baseline failed");
    std::atomic<bool> stop{false}, ready{false};
    std::atomic<int> reads{0}, invalid{0};
    std::thread reader([&] {
        do {
            try {
                const auto parsed = cas::json::parse(sharedContents(file));
                if (!parsed.contains("camera") || !parsed.at("camera").is_object()) ++invalid;
            } catch (...) { ++invalid; }
            ++reads; ready = true;
        } while (!stop);
    });
    struct StopReader {
        std::atomic<bool>& stop; std::thread& thread;
        ~StopReader() { stop = true; if (thread.joinable()) thread.join(); }
    } join{stop, reader};
    while (!ready) std::this_thread::yield();
    for (int i=0; i<30; ++i)
        require(store.set("camera", {{"threshold", i%2 ? .2 : .8}}, output, 0).empty(), "atomic replacement failed");
    stop = true;
    reader.join();
    require(reads > 0 && invalid == 0, "reader observed missing or partial JSON during replacement");
}
}

int main(int argc, char** argv) {
    const auto parent = fs::absolute(argc > 1 ? fs::path(argv[1]) : fs::current_path());
    fs::create_directories(parent);
    const auto root = parent / ("settings-test-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    // Only remove the unique child directory successfully created by this process.
    if (!fs::create_directory(root)) return 2;
    try {
        failedDirectory(root); successfulUpdates(root); failedReplacement(root); atomicReaders(root);
        fs::remove_all(root);
        std::cout << "settings store passed: " << checks << " checks\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        std::error_code ignored; fs::remove_all(root, ignored);
        return 1;
    }
}
