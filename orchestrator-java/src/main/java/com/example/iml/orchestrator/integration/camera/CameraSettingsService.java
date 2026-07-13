package com.example.iml.orchestrator.integration.camera;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.stream.CameraStreamServiceHolder;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Онлайн-настройки камеры через camera-worker (MVS / runtime state).
 */
public final class CameraSettingsService {

    private static final Logger LOG = LogManager.getLogger(CameraSettingsService.class);
    private static final Set<String> PATCH_KEYS = Set.of(
            "exposure_us",
            "gain_db",
            "gamma",
            "black_level",
            "capture_trigger_mode",
            "frame_timeout_ms"
    );

    private final CameraWorkersHolder workersHolder;
    private final CameraStreamServiceHolder streamHolder;
    private final CameraSettingsStore settingsStore;

    public CameraSettingsService(CameraWorkersHolder workersHolder, CameraStreamServiceHolder streamHolder) {
        this(workersHolder, streamHolder, null);
    }

    public CameraSettingsService(
            CameraWorkersHolder workersHolder,
            CameraStreamServiceHolder streamHolder,
            CameraSettingsStore settingsStore
    ) {
        this.workersHolder = workersHolder;
        this.streamHolder = streamHolder;
        this.settingsStore = settingsStore;
    }

    public Map<String, Object> getSettings(int cameraId) throws IOException {
        return workerResponse(cameraId, Map.of("op", "get_settings"));
    }

    public Map<String, Object> patchSettings(int cameraId, Map<String, Object> update) throws IOException {
        return applySettings(cameraId, update, true);
    }

    public void applyPersistedSettings() {
        if (settingsStore == null) {
            return;
        }
        for (Map.Entry<Integer, Map<String, Object>> entry : settingsStore.allSettings().entrySet()) {
            int cameraId = entry.getKey();
            Map<String, Object> settings = entry.getValue();
            if (settings.isEmpty()) {
                continue;
            }
            try {
                applySettings(cameraId, settings, false);
                LOG.info("camera persisted settings applied camera={} keys={}", cameraId, settings.keySet());
            } catch (Exception e) {
                LOG.warn("camera persisted settings apply failed camera={}: {}", cameraId, e.getMessage());
            }
        }
    }

    private Map<String, Object> applySettings(int cameraId, Map<String, Object> update, boolean persist) throws IOException {
        if (update == null || update.isEmpty()) {
            throw new IllegalArgumentException("at least one supported setting is required");
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("op", "set_settings");
        for (Map.Entry<String, Object> entry : update.entrySet()) {
            String key = entry.getKey();
            if (!PATCH_KEYS.contains(key)) {
                throw new IllegalArgumentException("unsupported setting: " + key);
            }
            header.put(key, entry.getValue());
        }
        if (header.size() == 1) {
            throw new IllegalArgumentException("at least one supported setting is required");
        }
        if (header.containsKey("capture_trigger_mode") && isStreaming(cameraId)) {
            throw new IllegalStateException("capture_trigger_mode cannot be changed while stream is active");
        }
        Map<String, Object> applied = workerResponse(cameraId, header);
        if (persist && settingsStore != null) {
            settingsStore.mergeAndSave(cameraId, update);
        }
        LOG.info("camera settings updated camera={} keys={} persisted={}", cameraId, header.keySet(), persist);
        return applied;
    }

    private boolean isStreaming(int cameraId) {
        CameraStreamService stream = streamHolder == null ? null : streamHolder.get();
        return stream != null && stream.isStreaming(cameraId);
    }

    private Map<String, Object> workerResponse(int cameraId, Map<String, Object> command) throws IOException {
        WorkerProcessSupervisor worker = workersHolder == null ? null : workersHolder.get(cameraId);
        if (worker == null) {
            throw new IllegalArgumentException("unknown camera_id: " + cameraId);
        }
        BinaryProtocol.Message response;
        synchronized (worker) {
            response = worker.command(command);
        }
        if (response == null) {
            throw new IOException("empty worker response");
        }
        if (response.type() == BinaryProtocol.MSG_ERROR) {
            throw new IOException(formatWorkerError(response));
        }
        if (response.type() != BinaryProtocol.MSG_RESPONSE) {
            throw new IOException("unexpected worker response type: " + response.type());
        }
        Map<String, Object> header = response.header();
        if (header == null || header.isEmpty()) {
            throw new IOException("worker response missing header");
        }
        return Map.copyOf(header);
    }

    private static String formatWorkerError(BinaryProtocol.Message response) {
        Map<String, Object> header = response.header();
        if (header != null && header.get("reason") != null) {
            return String.valueOf(header.get("reason"));
        }
        if (header != null && header.get("error") != null) {
            return String.valueOf(header.get("error"));
        }
        if (response.payload() != null && response.payload().length > 0) {
            return new String(response.payload(), StandardCharsets.UTF_8);
        }
        return "worker_error";
    }

    public static Map<String, Object> parsePatchBody(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        copyIfPresent(raw, patch, "exposure_us");
        copyIfPresent(raw, patch, "gain_db");
        copyIfPresent(raw, patch, "gamma");
        copyIfPresent(raw, patch, "black_level");
        copyIfPresent(raw, patch, "capture_trigger_mode");
        copyIfPresent(raw, patch, "frame_timeout_ms");
        return Map.copyOf(patch);
    }

    private static void copyIfPresent(Map<String, Object> raw, Map<String, Object> patch, String key) {
        if (!raw.containsKey(key)) {
            return;
        }
        Object value = raw.get(key);
        if (value == null) {
            return;
        }
        switch (key) {
            case "exposure_us", "black_level", "frame_timeout_ms" -> patch.put(key, YamlScalars.toInt(value, 0));
            case "gain_db", "gamma" -> patch.put(key, YamlScalars.toDouble(value, 0.0));
            case "capture_trigger_mode" -> {
                String mode = String.valueOf(value).trim();
                if (!mode.isEmpty()) {
                    patch.put(key, mode);
                }
            }
            default -> {
            }
        }
    }
}
