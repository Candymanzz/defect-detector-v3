package com.example.iml.orchestrator.integration.config;

import java.util.Map;

/**
 * YAML {@code analysis_profile} по {@code cameraId}: knobs UI, geometry-runtime и python inspect
 * должны читать один и тот же ключ, даже если {@code product_type} эталона другой.
 */
public final class CameraAnalysisProfiles {

    private static volatile Map<Integer, String> BY_CAMERA = Map.of();

    private CameraAnalysisProfiles() {
    }

    public static void setByCamera(Map<Integer, String> profiles) {
        BY_CAMERA = profiles == null || profiles.isEmpty() ? Map.of() : Map.copyOf(profiles);
    }

    public static Map<Integer, String> byCamera() {
        return BY_CAMERA;
    }

    /**
     * Профиль камеры из YAML; если не задан — {@code fallback} (обычно product_type цикла).
     */
    public static String resolve(int cameraId, String fallback) {
        if (cameraId >= 0) {
            String mapped = BY_CAMERA.get(cameraId);
            if (mapped != null) {
                String trimmed = mapped.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        if (fallback == null) {
            return null;
        }
        String trimmed = fallback.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
