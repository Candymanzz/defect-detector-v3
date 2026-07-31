package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class FrameArchiveSettingsStore {

    private static final Logger LOG = LogManager.getLogger(FrameArchiveSettingsStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int VERSION = 1;

    private final Path storagePath;
    private final int defaultMaxFrames;
    private final int maxAllowedFrames;
    private final Object lock = new Object();
    private int maxFramesPerCamera;

    private FrameArchiveSettingsStore(Path storagePath, int defaultMaxFrames, int maxAllowedFrames) {
        this.storagePath = storagePath;
        this.defaultMaxFrames = defaultMaxFrames;
        this.maxAllowedFrames = maxAllowedFrames;
        this.maxFramesPerCamera = clamp(defaultMaxFrames, maxAllowedFrames);
    }

    static FrameArchiveSettingsStore open(Path archiveRoot, int defaultMaxFrames, int maxAllowedFrames) throws IOException {
        Path storagePath = archiveRoot.resolve("settings.json");
        FrameArchiveSettingsStore store = new FrameArchiveSettingsStore(storagePath, defaultMaxFrames, maxAllowedFrames);
        store.load();
        return store;
    }

    int maxFramesPerCamera() {
        synchronized (lock) {
            return maxFramesPerCamera;
        }
    }

    void setMaxFramesPerCamera(int value) throws IOException {
        synchronized (lock) {
            maxFramesPerCamera = clamp(value, maxAllowedFrames);
            persistLocked();
        }
    }

    private void load() throws IOException {
        synchronized (lock) {
            maxFramesPerCamera = clamp(defaultMaxFrames, maxAllowedFrames);
            if (!Files.isRegularFile(storagePath)) {
                return;
            }
            Map<String, Object> root = JSON.readValue(Files.readString(storagePath), new TypeReference<>() {});
            if (root == null) {
                return;
            }
            Object raw = root.get("max_frames_per_camera");
            if (raw != null) {
                int parsed = raw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(raw));
                maxFramesPerCamera = clamp(parsed, maxAllowedFrames);
            }
            LOG.info(
                    "frame archive settings loaded path={} max_frames_per_camera={}",
                    storagePath.toAbsolutePath(),
                    maxFramesPerCamera
            );
        }
    }

    private void persistLocked() throws IOException {
        Files.createDirectories(storagePath.getParent());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("max_frames_per_camera", maxFramesPerCamera);
        CameraSettingsStore.kashPath(root, storagePath, JSON);
        LOG.info("frame archive settings saved path={} max_frames_per_camera={}", storagePath, maxFramesPerCamera);
    }

    private static int clamp(int value, int maxAllowed) {
        return Math.min(Math.max(value, 0), Math.max(0, maxAllowed));
    }
}
