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

    @SuppressWarnings("unchecked")
    public static Map<Integer, String> productTypeByCameraId(Map<String, Object> root) {
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
            byCamera.put(cameraId, String.valueOf(cam.getOrDefault("product_type", "camera-" + cameraId)));
        }
        return Map.copyOf(byCamera);
    }
}
