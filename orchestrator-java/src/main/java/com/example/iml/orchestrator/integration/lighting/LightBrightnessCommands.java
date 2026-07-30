package com.example.iml.orchestrator.integration.lighting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Разбор яркости вспышки из HTTP/WS (0…100%): глобально и по id endpoint.
 */
public final class LightBrightnessCommands {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LightBrightnessCommands() {
    }

    public static LightBrightnessUpdate parseBrightnessUpdate(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return LightBrightnessUpdate.empty();
        }
        try {
            JsonNode root = JSON.readTree(raw);
            return parseBrightnessUpdate(root);
        } catch (Exception e) {
            return LightBrightnessUpdate.empty();
        }
    }

    public static LightBrightnessUpdate parseBrightnessUpdate(JsonNode root) {
        if (root == null || !root.isObject()) {
            return LightBrightnessUpdate.empty();
        }
        Integer global = readPercentField(root.get("brightness_percent"));
        if (global == null) {
            global = readPercentField(root.get("default_brightness_percent"));
        }
        if (global == null) {
            JsonNode mv = root.get("brightness");
            if (mv != null && mv.isArray() && !mv.isEmpty()) {
                global = mvLe255ToUnified(mv.get(0).asInt(0));
            } else {
                global = readPercentField(root.get("brightness"));
            }
        }
        if (global == null) {
            global = readPercentField(root.get("value"));
        }
        Map<String, Integer> perEndpoint = parsePerEndpointNode(root.get("endpoints"));
        return new LightBrightnessUpdate(global, perEndpoint);
    }

    public static LightBrightnessUpdate parseBrightnessUpdateFromQuery(String query) {
        if (query == null || query.isBlank()) {
            return LightBrightnessUpdate.empty();
        }
        Integer global = null;
        String endpointId = null;
        Integer endpointPercent = null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if ("brightness_percent".equals(key) || "brightness".equals(key)) {
                global = parseIntPercent(value);
            } else if ("endpoint".equals(key) || "id".equals(key)) {
                endpointId = value;
            } else if ("endpoint_brightness_percent".equals(key)) {
                endpointPercent = parseIntPercent(value);
            }
        }
        Map<String, Integer> per = Map.of();
        if (endpointId != null && !endpointId.isBlank() && endpointPercent != null) {
            per = Map.of(endpointId, endpointPercent);
        }
        return new LightBrightnessUpdate(global, per);
    }

    public static LightBrightnessUpdate parseBrightnessUpdateFromWsPayload(JsonNode payload) {
        return parseBrightnessUpdate(payload);
    }

    private static Map<String, Integer> parsePerEndpointNode(JsonNode endpoints) {
        if (endpoints == null || endpoints.isNull()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        if (endpoints.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = endpoints.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                Integer p = readPercentField(e.getValue());
                if (p != null) {
                    out.put(e.getKey(), p);
                }
            }
            return out;
        }
        if (endpoints.isArray()) {
            for (JsonNode item : endpoints) {
                if (!item.isObject()) {
                    continue;
                }
                String id = item.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                Integer p = readPercentField(item.get("brightness_percent"));
                if (p == null) {
                    p = readPercentField(item.get("brightness"));
                }
                if (p != null) {
                    out.put(id, p);
                }
            }
        }
        return out;
    }

    private static Integer readPercentField(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return LightBrightnessScale.clampPercent(node.intValue());
        }
        if (node.isTextual()) {
            return parseIntPercent(node.asText());
        }
        return null;
    }

    private static Integer parseIntPercent(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LightBrightnessScale.clampPercent(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** MV-LE 0…255 → единые 0…100%. */
    public static int mvLe255ToUnified(int mvLe) {
        int b = Math.max(0, Math.min(255, mvLe));
        return LightBrightnessScale.clampPercent(Math.round(b * 100f / 255f));
    }
}
