package com.example.iml.orchestrator.integration.config;

import java.util.Map;

/** Inspection scale after alignment; camera IDs match the capture/inspection protocol. */
public final class InspectionScaleConfig {
    private InspectionScaleConfig() { }

    public static double resolve(Map<String, Object> pythonConfig, int cameraId) {
        if (pythonConfig == null) return 1.0;
        double fallback = validScale(pythonConfig.get("inspect_scale"), 1.0);
        Object raw = pythonConfig.get("inspect_scale_by_camera");
        if (!(raw instanceof Map<?, ?> overrides)) return fallback;
        // SnakeYAML produces integer keys; JSON configuration produces string keys.
        Object value = overrides.get(cameraId);
        if (value == null) value = overrides.get(String.valueOf(cameraId));
        return validScale(value, fallback);
    }

    private static double validScale(Object value, double fallback) {
        double scale = YamlScalars.toDouble(value, fallback);
        return Double.isFinite(scale) && scale > 0.0 && scale <= 1.0
                ? Math.max(0.1, scale) : fallback;
    }
}
