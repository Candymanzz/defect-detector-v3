package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.config.YamlMaps;
import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Camera flash specs from {@code light_servers} YAML. */
final class LightServersCameraMapper {

    private LightServersCameraMapper() {
    }

    static List<LightServersConfig.CameraFlashSpec> parseCameras(
            Map<String, Object> ls,
            int globalBrightness
    ) {
        Object raw = ls.get("cameras");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<LightServersConfig.CameraFlashSpec> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> em)) {
                continue;
            }
            Map<String, Object> m = YamlMaps.stringObjectMap(em);
            int cameraId = YamlScalars.toInt(m.get("camera_id"), YamlScalars.toInt(m.get("id"), -1));
            if (cameraId < 0 || !LightServersConfig.hasFlashHardware(cameraId)) {
                continue;
            }
            LightServersConfig.FlashMode mode = parseFlashMode(
                    String.valueOf(m.getOrDefault("mode", defaultModeNameForCameraId(cameraId)))
            );
            int percent = LightBrightnessScale.clampPercent(
                    YamlScalars.toInt(m.get("brightness_percent"), globalBrightness));
            int left = LightBrightnessScale.clampPercent(
                    YamlScalars.toInt(m.get("left_percent"), percent));
            int right = LightBrightnessScale.clampPercent(
                    YamlScalars.toInt(m.get("right_percent"), percent));
            out.add(new LightServersConfig.CameraFlashSpec(cameraId, mode, percent, left, right));
        }
        return List.copyOf(out);
    }

    static List<LightServersConfig.CameraFlashSpec> defaultCamerasFromRoot(
            Map<String, Object> root,
            int globalBrightness
    ) {
        List<Integer> ids = ConfiguredCameras.enabledIds(root);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<LightServersConfig.CameraFlashSpec> out = new ArrayList<>(ids.size());
        for (int cameraId : ids) {
            if (!LightServersConfig.hasFlashHardware(cameraId)) {
                continue;
            }
            LightServersConfig.FlashMode mode = defaultModeForCameraId(cameraId);
            out.add(new LightServersConfig.CameraFlashSpec(
                    cameraId, mode, globalBrightness, globalBrightness, globalBrightness));
        }
        return List.copyOf(out);
    }

    static List<LightServersConfig.CameraFlashSpec> migrateLegacyEndpoints(
            Map<String, Object> ls,
            int globalBrightness
    ) {
        Object raw = ls.get("endpoints");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        Map<Integer, LightServersConfig.CameraFlashSpec> byCamera = new LinkedHashMap<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> em)) {
                continue;
            }
            Map<String, Object> m = YamlMaps.stringObjectMap(em);
            if (!YamlScalars.toBool(m.get("enabled"), true)) {
                continue;
            }
            int percent = LightBrightnessScale.clampPercent(
                    YamlScalars.toInt(m.get("brightness_percent"), globalBrightness));
            int[] cameraIds = parseCameraIds(m.get("camera_ids"));
            if (cameraIds.length == 0) {
                continue;
            }
            for (int cameraId : cameraIds) {
                if (!LightServersConfig.hasFlashHardware(cameraId)) {
                    continue;
                }
                LightServersConfig.FlashMode mode = defaultModeForCameraId(cameraId);
                byCamera.put(cameraId, new LightServersConfig.CameraFlashSpec(
                        cameraId, mode, percent, percent, percent));
            }
        }
        return List.copyOf(byCamera.values());
    }

    private static LightServersConfig.FlashMode parseFlashMode(String modeStr) {
        String t = modeStr == null ? "" : modeStr.trim().toLowerCase();
        return switch (t) {
            case "single", "one", "1" -> LightServersConfig.FlashMode.SINGLE;
            default -> LightServersConfig.FlashMode.PAIR;
        };
    }

    private static LightServersConfig.FlashMode defaultModeForCameraId(int cameraId) {
        return isComFlashCamera(cameraId)
                ? LightServersConfig.FlashMode.SINGLE
                : LightServersConfig.FlashMode.PAIR;
    }

    private static String defaultModeNameForCameraId(int cameraId) {
        return isComFlashCamera(cameraId) ? "single" : "pair";
    }

    private static boolean isComFlashCamera(int cameraId) {
        return cameraId == 2 || cameraId == 7;
    }

    private static int[] parseCameraIds(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return new int[0];
        }
        int[] ids = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            ids[i] = YamlScalars.toInt(list.get(i), -1);
        }
        return ids;
    }
}
