package com.example.iml.orchestrator.integration.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ID камер из {@code cameras} в YAML/JSON (только {@code enabled: true}). */
public final class ConfiguredCameras {

    private ConfiguredCameras() {
    }

    @SuppressWarnings("unchecked")
    public static List<Integer> enabledIds(Map<String, Object> root) {
        if (root == null) {
            return List.of();
        }
        Object raw = root.get("cameras");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> em)) {
                continue;
            }
            Map<String, Object> cam = (Map<String, Object>) em;
            if (!YamlScalars.toBool(cam.get("enabled"), true)) {
                continue;
            }
            Object idObj = cam.get("id");
            if (!(idObj instanceof Number n)) {
                continue;
            }
            ids.add(n.intValue());
        }
        Collections.sort(ids);
        return List.copyOf(ids);
    }

    /**
     * Ключ профиля настроек анализа для камеры ({@code analysis_profile} в YAML).
     * Legacy {@code product_type} читается как fallback.
     */
    @SuppressWarnings("unchecked")
    public static String analysisProfileForCamera(Map<String, Object> camera, int cameraId) {
        if (camera == null) {
            return "camera-" + cameraId;
        }
        String profile = readNonBlankString(camera.get("analysis_profile"));
        if (profile != null) {
            return profile;
        }
        String legacy = readNonBlankString(camera.get("product_type"));
        if (legacy != null) {
            return legacy;
        }
        return "camera-" + cameraId;
    }

    @SuppressWarnings("unchecked")
    public static Map<Integer, String> analysisProfileByCameraId(Map<String, Object> root) {
        if (root == null) {
            return Map.of();
        }
        Object raw = root.get("cameras");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> byCamera = new LinkedHashMap<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> em)) {
                continue;
            }
            Map<String, Object> cam = (Map<String, Object>) em;
            if (!YamlScalars.toBool(cam.get("enabled"), true)) {
                continue;
            }
            Object idObj = cam.get("id");
            if (!(idObj instanceof Number n)) {
                continue;
            }
            int cameraId = n.intValue();
            byCamera.put(cameraId, analysisProfileForCamera(cam, cameraId));
        }
        return Map.copyOf(byCamera);
    }

    /** @deprecated use {@link #analysisProfileByCameraId} */
    @Deprecated
    public static Map<Integer, String> productTypeByCameraId(Map<String, Object> root) {
        return analysisProfileByCameraId(root);
    }

    private static String readNonBlankString(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
}
