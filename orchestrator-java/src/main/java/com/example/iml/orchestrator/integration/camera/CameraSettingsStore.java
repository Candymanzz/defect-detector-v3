package com.example.iml.orchestrator.integration.camera;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted runtime overrides for camera MVS settings (survives orchestrator restarts).
 */
public final class CameraSettingsStore {

    private static final Logger LOG = LogManager.getLogger(CameraSettingsStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int VERSION = 1;

    private final Path storagePath;
    private final Object lock = new Object();
    private final Map<Integer, Map<String, Object>> byCamera = new LinkedHashMap<>();

    private CameraSettingsStore(Path storagePath) {
        this.storagePath = storagePath;
    }

    public static CameraSettingsStore open(Path storagePath) throws IOException {
        CameraSettingsStore store = new CameraSettingsStore(storagePath);
        store.load();
        return store;
    }

    public Path storagePath() {
        return storagePath;
    }

    public Map<String, Object> settingsForCamera(int cameraId) {
        synchronized (lock) {
            Map<String, Object> settings = byCamera.get(cameraId);
            return settings == null ? Map.of() : Map.copyOf(settings);
        }
    }

    public Map<Integer, Map<String, Object>> allSettings() {
        synchronized (lock) {
            Map<Integer, Map<String, Object>> copy = new LinkedHashMap<>();
            for (Map.Entry<Integer, Map<String, Object>> entry : byCamera.entrySet()) {
                copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
            return Map.copyOf(copy);
        }
    }

    public void mergeAndSave(int cameraId, Map<String, Object> patch) throws IOException {
        if (patch == null || patch.isEmpty()) {
            return;
        }
        synchronized (lock) {
            Map<String, Object> current = byCamera.computeIfAbsent(cameraId, ignored -> new LinkedHashMap<>());
            current.putAll(patch);
            persistLocked();
        }
    }

    private void load() throws IOException {
        synchronized (lock) {
            byCamera.clear();
            if (storagePath == null || !Files.isRegularFile(storagePath)) {
                return;
            }
            Map<String, Object> root = JSON.readValue(Files.readString(storagePath), new TypeReference<>() {});
            Object camerasRaw = root == null ? null : root.get("cameras");
            if (!(camerasRaw instanceof Map<?, ?> camerasMap)) {
                return;
            }
            for (Map.Entry<?, ?> entry : camerasMap.entrySet()) {
                int cameraId = parseCameraId(entry.getKey());
                if (cameraId < 0 || !(entry.getValue() instanceof Map<?, ?> settingsRaw)) {
                    continue;
                }
                Map<String, Object> settings = new LinkedHashMap<>();
                for (Map.Entry<?, ?> settingEntry : settingsRaw.entrySet()) {
                    if (settingEntry.getKey() == null || settingEntry.getValue() == null) {
                        continue;
                    }
                    settings.put(String.valueOf(settingEntry.getKey()), settingEntry.getValue());
                }
                if (!settings.isEmpty()) {
                    byCamera.put(cameraId, settings);
                }
            }
            LOG.info("camera settings store loaded path={} cameras={}", storagePath.toAbsolutePath(), byCamera.size());
        }
    }

    private void persistLocked() throws IOException {
        if (storagePath == null) {
            return;
        }
        Files.createDirectories(storagePath.getParent());
        Map<String, Object> cameras = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Object>> entry : byCamera.entrySet()) {
            cameras.put(String.valueOf(entry.getKey()), Map.copyOf(entry.getValue()));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        root.put("cameras", cameras);
        Path tempPath = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), root);
        try {
            Files.move(tempPath, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(tempPath, storagePath, StandardCopyOption.REPLACE_EXISTING);
        }
        LOG.debug("camera settings store saved path={} cameras={}", storagePath.toAbsolutePath(), byCamera.size());
    }

    private static int parseCameraId(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
