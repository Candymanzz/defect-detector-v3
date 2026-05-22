package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Конфигурация подсветки через единый LightServer.v3 ({@code light_servers} или legacy {@code light_server}).
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
        /** IO Box / COM: POST /api/com/light */
        COM_IO,
        /** MV-LE по сети: POST /api/light */
        MV_LE
    }

    public record EndpointSpec(
            String id,
            boolean enabled,
            EndpointType type,
            String baseUrl,
            String comPort,
            String comPortsQuery,
            int deviceIndex,
            int[] channels,
            int brightnessPercent,
            int[] brightnessRaw
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
        int globalBrightness = LightBrightnessScale.clampPercent(brightness);
        int[] globalBrightnessRaw = parseBrightnessRaw(ls.get("brightness_raw"));
        List<EndpointSpec> endpoints = parseEndpoints(ls, globalBrightness, globalBrightnessRaw);
        return new LightServersConfig(enabled, failOnError, timeoutMs, settleDelayMs, flashLeadMs,
                globalBrightness, durationMs, endpoints);
    }

    public static LightServersConfig disabled() {
        return new LightServersConfig(false, false, 1500, 0, 0, 100, 180, List.of());
    }

    /** Базовый URL LightServer.v3 (первый enabled endpoint). */
    public String upstreamBaseUrl() {
        for (EndpointSpec ep : endpoints) {
            if (ep.enabled() && ep.baseUrl() != null && !ep.baseUrl().isBlank()) {
                return trimSlash(ep.baseUrl());
            }
        }
        return "http://127.0.0.1:5080";
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
            ep.put("type", "com_io");
            ep.put("base_url", lightServer.getOrDefault("base_url", "http://127.0.0.1:5080"));
            ep.put("com_port", lightServer.getOrDefault("com_port", "COM1"));
            endpoints.add(ep);
            ls.put("endpoints", endpoints);
        }
        return ls;
    }

    @SuppressWarnings("unchecked")
    private static List<EndpointSpec> parseEndpoints(Map<String, Object> ls, int globalBrightness, int[] globalBrightnessRaw) {
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
            EndpointType type = parseEndpointType(String.valueOf(m.getOrDefault("type", "com_io")));
            String baseUrl = trimSlash(String.valueOf(m.getOrDefault("base_url", "http://127.0.0.1:5080")));
            String comPort = String.valueOf(m.getOrDefault("com_port", "COM1")).trim();
            String comPortsQuery = m.containsKey("com_ports") ? String.valueOf(m.get("com_ports")) : null;
            int deviceIndex = YamlScalars.toInt(m.get("device_index"), 0);
            int[] channels = parseChannels(m.get("channels"));
            int epBrightness = LightBrightnessScale.clampPercent(
                    YamlScalars.toInt(m.get("brightness_percent"), globalBrightness));
            int[] epRaw = parseBrightnessRaw(m.get("brightness_raw"));
            if (epRaw == null) {
                epRaw = globalBrightnessRaw;
            }
            out.add(new EndpointSpec(id, en, type, baseUrl, comPort, comPortsQuery, deviceIndex, channels, epBrightness, epRaw));
        }
        return List.copyOf(out);
    }

    private static EndpointType parseEndpointType(String typeStr) {
        String t = typeStr == null ? "" : typeStr.trim().toLowerCase();
        return switch (t) {
            case "mv_le", "mv-le", "mvle" -> EndpointType.MV_LE;
            case "com_io", "com-io", "com", "trigger_inspection", "trigger-inspection" -> EndpointType.COM_IO;
            default -> EndpointType.COM_IO;
        };
    }

    private static int[] parseBrightnessRaw(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        int[] values = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            values[i] = Math.max(0, Math.min(255, YamlScalars.toInt(list.get(i), 0)));
        }
        return values;
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
