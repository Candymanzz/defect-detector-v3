package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Конфигурация подсветки через LightServer.v3: три типа URL — вкл, выкл, яркость по камерам.
 */
public record LightServersConfig(
        boolean enabled,
        boolean failOnError,
        int timeoutMs,
        int settleDelayMs,
        int flashLeadMs,
        int brightnessPercent,
        int durationMs,
        /** Постоянная подсветка: включить при старте, не гасить после capture. */
        boolean holdMode,
        String baseUrl,
        String onUrl,
        String offUrl,
        String brightnessPairUrl,
        String brightnessSingleUrl,
        String statusUrl,
        List<CameraFlashSpec> cameras
) {

    public enum FlashMode {
        PAIR,
        SINGLE
    }

    public record CameraFlashSpec(
            int cameraId,
            FlashMode mode,
            int brightnessPercent,
            int leftPercent,
            int rightPercent
    ) {
        public String endpointId() {
            return "camera-" + cameraId;
        }

        public int cameraNumber() {
            return cameraId + 1;
        }

        public int power255() {
            return LightBrightnessScale.toMvLeBrightness(brightnessPercent);
        }

        public int leftPower255() {
            return LightBrightnessScale.toMvLeBrightness(leftPercent);
        }

        public int rightPower255() {
            return LightBrightnessScale.toMvLeBrightness(rightPercent);
        }
    }

    @SuppressWarnings("unchecked")
    public static LightServersConfig fromRootYaml(Map<String, Object> root) {
        Map<String, Object> ls = null;
        Object multi = root == null ? null : root.get("light_servers");
        if (multi instanceof Map<?, ?> m) {
            ls = (Map<String, Object>) m;
        }
        if (ls == null) {
            Object legacy = root == null ? null : root.get("light_server");
            if (legacy instanceof Map<?, ?> m) {
                ls = legacyFromSingle((Map<String, Object>) m);
            }
        }
        if (ls == null) {
            return disabled();
        }
        boolean enabled = YamlScalars.toBool(ls.get("enabled"), false);
        boolean failOnError = YamlScalars.toBool(ls.get("fail_on_error"), false);
        int timeoutMs = YamlScalars.toInt(ls.get("timeout_ms"), 1500);
        int settleDelayMs = YamlScalars.toInt(ls.get("settle_delay_ms"), 100);
        int flashLeadMs = Math.max(0, YamlScalars.toInt(ls.get("flash_lead_ms"), 0));
        int brightness = YamlScalars.toInt(ls.get("brightness_percent"), YamlScalars.toInt(ls.get("brightness"), 100));
        int durationMs = YamlScalars.toInt(ls.get("duration_ms"), 180);
        boolean holdMode = YamlScalars.toBool(ls.get("hold_mode"), false);
        int globalBrightness = LightBrightnessScale.clampPercent(brightness);

        String baseUrl = trimSlash(String.valueOf(ls.getOrDefault("base_url", "http://127.0.0.1:5080")));
        UrlPaths urls = parseUrls(ls, baseUrl);
        List<CameraFlashSpec> cameras = parseCameras(ls, root, globalBrightness);

        if (cameras.isEmpty() && ls.containsKey("endpoints")) {
            cameras = migrateLegacyEndpoints(ls, globalBrightness);
        }
        if (cameras.isEmpty() && root != null) {
            cameras = defaultCamerasFromRoot(root, globalBrightness);
        }

        return new LightServersConfig(
                enabled,
                failOnError,
                timeoutMs,
                settleDelayMs,
                flashLeadMs,
                globalBrightness,
                durationMs,
                holdMode,
                baseUrl,
                urls.onUrl(),
                urls.offUrl(),
                urls.brightnessPairUrl(),
                urls.brightnessSingleUrl(),
                urls.statusUrl(),
                cameras
        );
    }

    public static LightServersConfig disabled() {
        return new LightServersConfig(
                false, false, 1500, 0, 0, 100, 180, false,
                "http://127.0.0.1:5080",
                LightServerV3Http.PATH_COM_LIGHT,
                LightServerV3Http.PATH_COM_LIGHT,
                LightServerV3Http.PATH_CAMERA_FLASH_PAIR,
                LightServerV3Http.PATH_CAMERA_FLASH_SINGLE,
                LightServerV3Http.PATH_COM_LIGHT,
                List.of()
        );
    }

    public boolean hasCameras() {
        return !cameras.isEmpty();
    }

    public CameraFlashSpec camera(int cameraId) {
        for (CameraFlashSpec spec : cameras) {
            if (spec.cameraId() == cameraId) {
                return spec;
            }
        }
        return null;
    }

    /** Базовый URL LightServer.v3. */
    public String upstreamBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:5080" : baseUrl;
    }

    /** {@code flash_lead_ms} из {@code light_servers} или legacy {@code light_server}. */
    public static int flashLeadMsFromRoot(Map<String, Object> root) {
        int v = readFlashLead(root == null ? null : root.get("light_servers"));
        if (v > 0) {
            return v;
        }
        return readFlashLead(root == null ? null : root.get("light_server"));
    }

    private static int readFlashLead(Object section) {
        if (section instanceof Map<?, ?> m) {
            return Math.max(0, YamlScalars.toInt(m.get("flash_lead_ms"), 0));
        }
        return 0;
    }

    private record UrlPaths(
            String onUrl,
            String offUrl,
            String brightnessPairUrl,
            String brightnessSingleUrl,
            String statusUrl
    ) {
    }

    @SuppressWarnings("unchecked")
    private static UrlPaths parseUrls(Map<String, Object> ls, String baseUrl) {
        Object urlsObj = ls.get("urls");
        Map<String, Object> urls = urlsObj instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

        String on = resolveUrl(
                urls.get("on"),
                ls.get("on_url"),
                LightServerV3Http.PATH_COM_LIGHT,
                baseUrl
        );
        String off = resolveUrl(
                urls.get("off"),
                ls.get("off_url"),
                LightServerV3Http.PATH_COM_LIGHT,
                baseUrl
        );
        String pair = resolveUrl(
                urls.get("brightness_pair"),
                ls.get("brightness_pair_url"),
                LightServerV3Http.PATH_CAMERA_FLASH_PAIR,
                baseUrl
        );
        String single = resolveUrl(
                urls.get("brightness_single"),
                ls.get("brightness_single_url"),
                LightServerV3Http.PATH_CAMERA_FLASH_SINGLE,
                baseUrl
        );
        String status = resolveUrl(
                urls.get("status"),
                ls.get("status_url"),
                LightServerV3Http.PATH_COM_LIGHT,
                baseUrl
        );
        return new UrlPaths(on, off, pair, single, status);
    }

    private static String resolveUrl(Object nested, Object flat, String defaultPath, String baseUrl) {
        String raw = nested != null ? String.valueOf(nested) : flat != null ? String.valueOf(flat) : defaultPath;
        raw = raw.trim();
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return trimSlash(raw);
        }
        if (raw.isEmpty()) {
            raw = defaultPath;
        }
        if (!raw.startsWith("/")) {
            raw = "/" + raw;
        }
        return trimSlash(baseUrl) + raw;
    }

    @SuppressWarnings("unchecked")
    private static List<CameraFlashSpec> parseCameras(
            Map<String, Object> ls,
            Map<String, Object> root,
            int globalBrightness
    ) {
        Object raw = ls.get("cameras");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<CameraFlashSpec> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> em)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) em;
            int cameraId = YamlScalars.toInt(m.get("camera_id"), YamlScalars.toInt(m.get("id"), -1));
            if (cameraId < 0 || !hasFlashHardware(cameraId)) {
                continue;
            }
            FlashMode mode = parseFlashMode(String.valueOf(m.getOrDefault("mode", defaultModeNameForCameraId(cameraId))));
            int percent = LightBrightnessScale.clampPercent(
                    YamlScalars.toInt(m.get("brightness_percent"), globalBrightness));
            int left = LightBrightnessScale.clampPercent(
                    YamlScalars.toInt(m.get("left_percent"), percent));
            int right = LightBrightnessScale.clampPercent(
                    YamlScalars.toInt(m.get("right_percent"), percent));
            out.add(new CameraFlashSpec(cameraId, mode, percent, left, right));
        }
        return List.copyOf(out);
    }

    private static List<CameraFlashSpec> defaultCamerasFromRoot(Map<String, Object> root, int globalBrightness) {
        List<Integer> ids = ConfiguredCameras.enabledIds(root);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<CameraFlashSpec> out = new ArrayList<>(ids.size());
        for (int cameraId : ids) {
            if (!hasFlashHardware(cameraId)) {
                continue;
            }
            FlashMode mode = defaultModeForCameraId(cameraId);
            out.add(new CameraFlashSpec(cameraId, mode, globalBrightness, globalBrightness, globalBrightness));
        }
        return List.copyOf(out);
    }

    @SuppressWarnings("unchecked")
    private static List<CameraFlashSpec> migrateLegacyEndpoints(Map<String, Object> ls, int globalBrightness) {
        Object raw = ls.get("endpoints");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        Map<Integer, CameraFlashSpec> byCamera = new LinkedHashMap<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> em)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) em;
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
                if (!hasFlashHardware(cameraId)) {
                    continue;
                }
                FlashMode mode = defaultModeForCameraId(cameraId);
                byCamera.put(cameraId, new CameraFlashSpec(cameraId, mode, percent, percent, percent));
            }
        }
        return List.copyOf(byCamera.values());
    }

    private static Map<String, Object> legacyFromSingle(Map<String, Object> lightServer) {
        Map<String, Object> ls = new LinkedHashMap<>(lightServer);
        if (!ls.containsKey("base_url")) {
            ls.put("base_url", "http://127.0.0.1:5080");
        }
        return ls;
    }

    private static FlashMode parseFlashMode(String modeStr) {
        String t = modeStr == null ? "" : modeStr.trim().toLowerCase();
        return switch (t) {
            case "single", "one", "1" -> FlashMode.SINGLE;
            default -> FlashMode.PAIR;
        };
    }

    /**
     * Вспышки: id 0–7 (camera_number 1–8) — Ethernet MV-LE pair;
     * id 8–9 (номера 9–10) — COM1 single (центральные).
     * См. config/blocks/51-light-hardware.yaml.
     */
    static boolean hasFlashHardware(int cameraId) {
        return cameraId >= 0 && cameraId <= 9;
    }

    /** 0–7: pair (2 канала); 8–9: single (1 канал COM). */
    private static FlashMode defaultModeForCameraId(int cameraId) {
        return cameraId >= 8 ? FlashMode.SINGLE : FlashMode.PAIR;
    }

    private static String defaultModeNameForCameraId(int cameraId) {
        return cameraId >= 8 ? "single" : "pair";
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

    private static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
