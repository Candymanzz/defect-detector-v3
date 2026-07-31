package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.config.YamlMaps;
import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * YAML → {@link LightServersConfig}: парсинг {@code light_servers} / legacy {@code light_server}.
 */
public final class LightServersConfigMapper {

    private LightServersConfigMapper() {
    }

    public static LightServersConfig fromRootYaml(Map<String, Object> root) {
        Map<String, Object> ls = null;
        Object multi = root == null ? null : root.get("light_servers");
        if (multi instanceof Map<?, ?>) {
            ls = YamlMaps.stringObjectMap(multi);
        }
        if (ls == null) {
            Object legacy = root == null ? null : root.get("light_server");
            if (legacy instanceof Map<?, ?>) {
                ls = legacyFromSingle(YamlMaps.stringObjectMap(legacy));
            }
        }
        if (ls == null) {
            return LightServersConfig.disabled();
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
        List<LightServersConfig.CameraFlashSpec> cameras = LightServersCameraMapper.parseCameras(ls, globalBrightness);

        if (cameras.isEmpty() && ls.containsKey("endpoints")) {
            cameras = LightServersCameraMapper.migrateLegacyEndpoints(ls, globalBrightness);
        }
        if (cameras.isEmpty() && root != null) {
            cameras = LightServersCameraMapper.defaultCamerasFromRoot(root, globalBrightness);
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

    static int readFlashLead(Object section) {
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

    private static UrlPaths parseUrls(Map<String, Object> ls, String baseUrl) {
        Map<String, Object> urls = YamlMaps.stringObjectMap(ls.get("urls"));

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

    private static Map<String, Object> legacyFromSingle(Map<String, Object> lightServer) {
        Map<String, Object> ls = new LinkedHashMap<>(lightServer);
        if (!ls.containsKey("base_url")) {
            ls.put("base_url", "http://127.0.0.1:5080");
        }
        return ls;
    }

    private static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
