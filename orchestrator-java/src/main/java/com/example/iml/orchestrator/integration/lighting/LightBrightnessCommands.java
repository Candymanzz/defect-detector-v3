package com.example.iml.orchestrator.integration.lighting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Разбор яркости вспышки из HTTP/WS (0…100%, единая шкала оркестратора).
 */
public final class LightBrightnessCommands {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LightBrightnessCommands() {
    }

    public static Integer parseUnifiedPercentFromHttpBody(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(raw);
            if (!root.isObject()) {
                return null;
            }
            Integer p = readPercentField(root.get("brightness_percent"));
            if (p != null) {
                return p;
            }
            JsonNode mv = root.get("brightness");
            if (mv != null && mv.isArray() && !mv.isEmpty()) {
                return mvLe255ToUnified(mv.get(0).asInt(0));
            }
            p = readPercentField(root.get("brightness"));
            if (p != null) {
                return p;
            }
            p = readPercentField(root.get("value"));
            if (p != null) {
                return p;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static Integer parseUnifiedPercentFromQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if ("brightness_percent".equals(key) || "brightness".equals(key)) {
                return parseIntPercent(value);
            }
        }
        return null;
    }

    public static Integer parseUnifiedPercentFromWsPayload(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return null;
        }
        Integer p = readPercentField(payload.get("brightness_percent"));
        if (p != null) {
            return p;
        }
        return readPercentField(payload.get("brightness"));
    }

    public static void applyIfPresent(LightTriggerClient lightClient, Integer percent) {
        if (lightClient == null || percent == null) {
            return;
        }
        lightClient.setBrightnessPercent(percent);
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

    @SuppressWarnings("unchecked")
    public static Integer parseUnifiedPercentFromMap(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return parseUnifiedPercentFromHttpBody(JSON.writeValueAsBytes(body));
        } catch (Exception e) {
            return null;
        }
    }
}
