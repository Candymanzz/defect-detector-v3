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

    public static List<Integer> enabledIds(Map<String, Object> root) {
        if (root == null) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (Map<String, Object> cam : YamlMaps.listOfStringObjectMaps(root.get("cameras"))) {
            if (!YamlScalars.toBool(cam.get("enabled"), true)) {
                continue;
            }
            Integer id = idOrNull(cam);
            if (id == null) {
                continue;
            }
            ids.add(id);
        }
        Collections.sort(ids);
        return List.copyOf(ids);
    }

    /** Camera {@code id} from a YAML camera map; throws if missing/invalid. */
    public static int requireId(Map<String, Object> camera) {
        Integer id = idOrNull(camera);
        if (id == null) {
            throw new IllegalArgumentException("camera id missing or not a number");
        }
        return id;
    }

    /** Camera {@code id} from a YAML camera map, or {@code null} if missing/invalid. */
    public static Integer idOrNull(Map<String, Object> camera) {
        if (camera == null) {
            return null;
        }
        Object idObj = camera.get("id");
        if (!(idObj instanceof Number n)) {
            return null;
        }
        return n.intValue();
    }

    /**
     * Ключ профиля настроек анализа для камеры ({@code analysis_profile} в YAML).
     * Legacy {@code product_type} читается как fallback.
     */
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

    public static Map<Integer, String> analysisProfileByCameraId(Map<String, Object> root) {
        if (root == null) {
            return Map.of();
        }
        Map<Integer, String> byCamera = new LinkedHashMap<>();
        for (Map<String, Object> cam : YamlMaps.listOfStringObjectMaps(root.get("cameras"))) {
            if (!YamlScalars.toBool(cam.get("enabled"), true)) {
                continue;
            }
            Integer cameraId = idOrNull(cam);
            if (cameraId == null) {
                continue;
            }
            byCamera.put(cameraId, analysisProfileForCamera(cam, cameraId));
        }
        return Map.copyOf(byCamera);
    }

    private static String readNonBlankString(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }
}
