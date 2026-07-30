package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.nio.file.Path;
import java.util.Map;

public record FrameArchiveConfig(
        boolean enabled,
        Path directory,
        int defaultMaxFramesPerCamera,
        int maxAllowedFramesPerCamera
) {
    private static final int DEFAULT_MAX_FRAMES = 20;
    private static final int DEFAULT_MAX_ALLOWED = 100;

    public static FrameArchiveConfig fromRootYaml(Map<String, Object> root, boolean isWindows) {
        if (root == null) {
            return disabled(isWindows);
        }
        Object raw = root.get("frame_archive");
        if (!(raw instanceof Map<?, ?> map)) {
            return disabled(isWindows);
        }
        boolean enabled = YamlScalars.toBool(map.get("enabled"), true);
        Object directoryObj = map.get(isWindows ? "directory_windows" : "directory_linux");
        String directoryRaw = directoryObj != null
                ? String.valueOf(directoryObj)
                : (isWindows ? "D:\\frame" : "/tmp/iml-frame-archive");
        Path directory = Path.of(directoryRaw.trim()).toAbsolutePath().normalize();
        int defaultMaxFrames = Math.max(
                0,
                YamlScalars.toInt(map.get("default_max_frames_per_camera"), DEFAULT_MAX_FRAMES)
        );
        int maxAllowed = Math.max(
                defaultMaxFrames,
                YamlScalars.toInt(map.get("max_allowed_frames_per_camera"), DEFAULT_MAX_ALLOWED)
        );
        return new FrameArchiveConfig(enabled, directory, defaultMaxFrames, maxAllowed);
    }

    private static FrameArchiveConfig disabled(boolean isWindows) {
        Path directory = isWindows ? Path.of("D:\\frame") : Path.of("/tmp/iml-frame-archive");
        return new FrameArchiveConfig(false, directory.toAbsolutePath().normalize(), DEFAULT_MAX_FRAMES, DEFAULT_MAX_ALLOWED);
    }
}
