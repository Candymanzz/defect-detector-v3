package com.example.iml.orchestrator.integration.trigger.parse;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

public final class PlainUdpTriggerMessageParser implements UdpTriggerMessageParser {

    @Override
    public Optional<InspectionTriggerEvent> parse(
            byte[] payload,
            int length,
            InetSocketAddress remote,
            int defaultCameraId
    ) {
        if (length == 1) {
            return Optional.of(InspectionTriggerEvent.of(payload[0] & 0xFF, "udp"));
        }
        String text = new String(payload, 0, length, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if ("TRIGGER".equals(upper) || "INSPECT".equals(upper) || "1".equals(upper)) {
            return Optional.of(InspectionTriggerEvent.of(defaultCameraId, "udp"));
        }
        if (upper.startsWith("TRIGGER ")) {
            return parseCameraToken(upper.substring("TRIGGER ".length()).trim(), defaultCameraId);
        }
        if (upper.startsWith("INSPECT ")) {
            return parseCameraToken(upper.substring("INSPECT ".length()).trim(), defaultCameraId);
        }
        try {
            int cameraId = Integer.parseInt(text);
            return Optional.of(InspectionTriggerEvent.of(cameraId, "udp"));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<InspectionTriggerEvent> parseCameraToken(String token, int defaultCameraId) {
        if (token.isEmpty()) {
            return Optional.of(InspectionTriggerEvent.of(defaultCameraId, "udp"));
        }
        try {
            return Optional.of(InspectionTriggerEvent.of(Integer.parseInt(token), "udp"));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
