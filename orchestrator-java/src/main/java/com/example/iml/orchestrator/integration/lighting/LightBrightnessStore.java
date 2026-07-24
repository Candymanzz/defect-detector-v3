package com.example.iml.orchestrator.integration.lighting;

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
 * Persisted runtime overrides for flash brightness (survives orchestrator restarts).
 */
public final class LightBrightnessStore {

    private static final Logger LOG = LogManager.getLogger(LightBrightnessStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int VERSION = 1;

    private final Path storagePath;
    private final Object lock = new Object();
    private Integer defaultBrightnessPercent;
    private Boolean constantFlashMode;
    private final Map<String, Integer> endpoints = new LinkedHashMap<>();

    private LightBrightnessStore(Path storagePath) {
        this.storagePath = storagePath;
    }

    public static LightBrightnessStore open(Path storagePath) throws IOException {
        LightBrightnessStore store = new LightBrightnessStore(storagePath);
        store.load();
        return store;
    }

    public Path storagePath() {
        return storagePath;
    }

    public LightBrightnessUpdate toUpdate() {
        synchronized (lock) {
            if (defaultBrightnessPercent == null && endpoints.isEmpty()) {
                return LightBrightnessUpdate.empty();
            }
            return new LightBrightnessUpdate(defaultBrightnessPercent, Map.copyOf(endpoints));
        }
    }

    public void saveFromClient(LightTriggerClient client) throws IOException {
        if (client == null) {
            return;
        }
        synchronized (lock) {
            defaultBrightnessPercent = client.brightnessPercent();
            constantFlashMode = client.isConstantFlashMode();
            endpoints.clear();
            endpoints.putAll(client.brightnessByEndpoint());
            persistLocked();
        }
    }

    private void load() throws IOException {
        synchronized (lock) {
            defaultBrightnessPercent = null;
            constantFlashMode = null;
            endpoints.clear();
            if (storagePath == null || !Files.isRegularFile(storagePath)) {
                return;
            }
            // Windows editors / PowerShell often write UTF-8 BOM; Jackson rejects 0xFEFF.
            String raw = Files.readString(storagePath);
            if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
                raw = raw.substring(1);
            }
            Map<String, Object> root = JSON.readValue(raw, new TypeReference<>() {});
            if (root == null) {
                return;
            }
            Object defaultRaw = root.get("default_brightness_percent");
            if (defaultRaw != null) {
                defaultBrightnessPercent = LightBrightnessScale.clampPercent(
                        defaultRaw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(defaultRaw))
                );
            }
            Object endpointsRaw = root.get("endpoints");
            Object modeRaw = root.get("constant_flash_mode");
            if (modeRaw != null) {
                constantFlashMode = modeRaw instanceof Boolean value
                        ? value
                        : Boolean.parseBoolean(String.valueOf(modeRaw));
            }
            if (endpointsRaw instanceof Map<?, ?> endpointsMap) {
                for (Map.Entry<?, ?> entry : endpointsMap.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    int percent = LightBrightnessScale.clampPercent(
                            entry.getValue() instanceof Number number
                                    ? number.intValue()
                                    : Integer.parseInt(String.valueOf(entry.getValue()))
                    );
                    endpoints.put(String.valueOf(entry.getKey()), percent);
                }
            }
            LOG.info(
                    "light brightness store loaded path={} default={} endpoints={}",
                    storagePath.toAbsolutePath(),
                    defaultBrightnessPercent,
                    endpoints.size()
            );
        }
    }

    private void persistLocked() throws IOException {
        if (storagePath == null) {
            return;
        }
        Files.createDirectories(storagePath.getParent());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", VERSION);
        if (defaultBrightnessPercent != null) {
            root.put("default_brightness_percent", defaultBrightnessPercent);
        }
        if (constantFlashMode != null) {
            root.put("constant_flash_mode", constantFlashMode);
        }
        root.put("endpoints", Map.copyOf(endpoints));
        Path tempPath = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), root);
        try {
            Files.move(tempPath, storagePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(tempPath, storagePath, StandardCopyOption.REPLACE_EXISTING);
        }
        LOG.debug(
                "light brightness store saved path={} default={} endpoints={}",
                storagePath.toAbsolutePath(),
                defaultBrightnessPercent,
                endpoints.size()
        );
    }

    public boolean constantFlashMode() {
        synchronized (lock) {
            return Boolean.TRUE.equals(constantFlashMode);
        }
    }
}
