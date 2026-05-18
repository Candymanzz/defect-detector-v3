package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Конфигурация одной или нескольких подсистем подсветки ({@code light_servers} или legacy {@code light_server}).
 */
public record LightServersConfig(
        boolean enabled,
        boolean failOnError,
        int timeoutMs,
        int settleDelayMs,
        int flashLeadMs,
        int brightnessPercent,
        int durationMs,
        List<EndpointSpec> endpoints
) {

    public enum EndpointType {
        TRIGGER_INSPECTION,
        MV_LE
    }

    public record EndpointSpec(
            String id,
            boolean enabled,
            EndpointType type,
            String baseUrl,
            String triggerPath,
            String statusPath,
            int deviceIndex,
            int[] channels
    ) {
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
        List<EndpointSpec> endpoints = parseEndpoints(ls);
        return new LightServersConfig(enabled, failOnError, timeoutMs, settleDelayMs, flashLeadMs,
                LightBrightnessScale.clampPercent(brightness), durationMs, endpoints);
    }

    public static LightServersConfig disabled() {
        return new LightServersConfig(false, false, 1500, 0, 0, 100, 180, List.of());
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

    private static Map<String, Object> legacyFromSingle(Map<String, Object> lightServer) {
        Map<String, Object> ls = new java.util.LinkedHashMap<>(lightServer);
        if (!ls.containsKey("endpoints")) {
            List<Map<String, Object>> endpoints = new ArrayList<>();
            Map<String, Object> ep = new java.util.LinkedHashMap<>();
            ep.put("id", "light-com");
            ep.put("enabled", true);
            ep.put("type", "trigger_inspection");
            ep.put("base_url", lightServer.getOrDefault("base_url", "http://127.0.0.1:5079"));
            ep.put("trigger_path", lightServer.getOrDefault("trigger_path", "/api/light/trigger-inspection"));
            ep.put("status_path", "/api/light/status");
            endpoints.add(ep);
            ls.put("endpoints", endpoints);
        }
        return ls;
    }

    @SuppressWarnings("unchecked")
    private static List<EndpointSpec> parseEndpoints(Map<String, Object> ls) {
        Object raw = ls.get("endpoints");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<EndpointSpec> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> em)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) em;
            String id = String.valueOf(m.getOrDefault("id", "light"));
            boolean en = YamlScalars.toBool(m.get("enabled"), true);
            String typeStr = String.valueOf(m.getOrDefault("type", "trigger_inspection")).trim().toLowerCase();
            EndpointType type = "mv_le".equals(typeStr) || "mv-le".equals(typeStr) ? EndpointType.MV_LE : EndpointType.TRIGGER_INSPECTION;
            String baseUrl = trimSlash(String.valueOf(m.getOrDefault("base_url", "http://127.0.0.1:5079")));
            String triggerPath = String.valueOf(m.getOrDefault("trigger_path", "/api/light/trigger-inspection"));
            String statusPath = String.valueOf(m.getOrDefault("status_path", "/api/light/status"));
            int deviceIndex = YamlScalars.toInt(m.get("device_index"), 1);
            int[] channels = parseChannels(m.get("channels"));
            out.add(new EndpointSpec(id, en, type, baseUrl, triggerPath, statusPath, deviceIndex, channels));
        }
        return List.copyOf(out);
    }

    private static int[] parseChannels(Object raw) {
        if (raw instanceof List<?> list && !list.isEmpty()) {
            int[] ch = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                ch[i] = YamlScalars.toInt(list.get(i), i + 1);
            }
            return ch;
        }
        return new int[]{1, 2, 3, 4};
    }

    private static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
