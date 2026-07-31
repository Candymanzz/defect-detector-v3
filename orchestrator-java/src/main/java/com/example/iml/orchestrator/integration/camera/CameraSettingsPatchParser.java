package com.example.iml.orchestrator.integration.camera;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.LinkedHashMap;
import java.util.Map;

/** Parses HTTP/JSON patch bodies into supported camera setting keys. */
final class CameraSettingsPatchParser {

    private CameraSettingsPatchParser() {
    }

    static Map<String, Object> parsePatchBody(Map<String, Object> raw) {
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
