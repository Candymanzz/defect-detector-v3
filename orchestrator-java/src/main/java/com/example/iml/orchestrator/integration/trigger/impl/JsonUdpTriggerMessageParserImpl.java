package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.api.UdpTriggerMessageParser;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class JsonUdpTriggerMessageParserImpl implements UdpTriggerMessageParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    @SuppressWarnings("unused")
    public Optional<InspectionTriggerEvent> parse(
            byte[] payload,
            int length,
            InetSocketAddress remote,
            int defaultCameraId
    ) {
        try {
            String text = new String(payload, 0, length, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                return Optional.empty();
            }
            JsonNode root = MAPPER.readTree(text);
            if (root.has("action")) {
                String action = root.get("action").asText("").trim().toLowerCase();
                if (!action.isEmpty() && !"inspect".equals(action) && !"trigger".equals(action)) {
                    return Optional.empty();
                }
            }
            int cameraId = defaultCameraId;
            if (root.has("camera_id")) {
                cameraId = root.get("camera_id").asInt(defaultCameraId);
            } else if (root.has("cameraId")) {
                cameraId = root.get("cameraId").asInt(defaultCameraId);
            }
            return Optional.of(InspectionTriggerEvent.of(cameraId, "udp"));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
