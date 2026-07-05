package com.example.iml.orchestrator.integration.trigger.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Парсер UDP-пакетов {@code IoInputMonitor}: JSON, text_di, byte_di и legacy 0/1.
 */
public final class IoInputDiChangeParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IoInputDiChangeParser() {
    }

    public static Optional<IoInputDiChange> parse(byte[] payload, int length, String format) {
        if (payload == null || length <= 0) {
            return Optional.empty();
        }
        String normalized = format == null ? "json" : format.trim().toLowerCase();
        return switch (normalized) {
            case "text_di" -> parseTextDi(payload, length);
            case "byte_di" -> parseByteDi(payload, length);
            case "byte", "text" -> parseLegacy(payload, length);
            default -> parseJson(payload, length);
        };
    }

    private static Optional<IoInputDiChange> parseJson(byte[] payload, int length) {
        try {
            String text = new String(payload, 0, length, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                return Optional.empty();
            }
            JsonNode root = MAPPER.readTree(text);
            if (!root.has("di") || !root.has("value")) {
                return Optional.empty();
            }
            int di = root.get("di").asInt(-1);
            int value = root.get("value").asInt(-1);
            if (di < 1 || di > 8 || (value != 0 && value != 1)) {
                return Optional.empty();
            }
            return Optional.of(new IoInputDiChange(di, value == 1));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<IoInputDiChange> parseTextDi(byte[] payload, int length) {
        String text = new String(payload, 0, length, StandardCharsets.UTF_8).trim();
        int colon = text.indexOf(':');
        if (colon <= 0 || colon >= text.length() - 1) {
            return Optional.empty();
        }
        try {
            int di = Integer.parseInt(text.substring(0, colon).trim());
            int value = Integer.parseInt(text.substring(colon + 1).trim());
            if (di < 1 || di > 8 || (value != 0 && value != 1)) {
                return Optional.empty();
            }
            return Optional.of(new IoInputDiChange(di, value == 1));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<IoInputDiChange> parseByteDi(byte[] payload, int length) {
        if (length < 2) {
            return Optional.empty();
        }
        int di = payload[0] & 0xFF;
        int value = payload[1] & 0xFF;
        if (di < 1 || di > 8 || (value != 0 && value != 1)) {
            return Optional.empty();
        }
        return Optional.of(new IoInputDiChange(di, value == 1));
    }

    private static Optional<IoInputDiChange> parseLegacy(byte[] payload, int length) {
        if (length == 1) {
            int value = payload[0] & 0xFF;
            if (value == 0 || value == 1) {
                return Optional.of(new IoInputDiChange(-1, value == 1));
            }
            return Optional.empty();
        }
        String text = new String(payload, 0, length, StandardCharsets.UTF_8).trim();
        if ("0".equals(text)) {
            return Optional.of(new IoInputDiChange(-1, false));
        }
        if ("1".equals(text)) {
            return Optional.of(new IoInputDiChange(-1, true));
        }
        return Optional.empty();
    }
}
