package com.example.iml.orchestrator.integration.clientws.outbound.messages;

import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsJsonSerializationException;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundJson;
import com.example.iml.orchestrator.integration.clientws.protocol.WsMessageTypes;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.clientws.util.WsTextUtil;
import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Stream / ack / session / PLC / error / hello outbound JSON builders.
 */
public final class ControlOutboundJson {

    private final ClientWsConfig cfg;
    private final Supplier<ClientWsSessionState> sessionState;

    public ControlOutboundJson(ClientWsConfig cfg, Supplier<ClientWsSessionState> sessionState) {
        this.cfg = cfg;
        this.sessionState = sessionState;
    }

    public String buildHelloJson() throws ClientWsJsonSerializationException {
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_HELLO);
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("session_state", sessionState.get().name());
        payload.put("server_ts_ms", System.currentTimeMillis());
        root.set("payload", payload);
        return WsOutboundJson.writeJson(root);
    }

    public String buildStreamStartedJson(int cameraId, int maxFps, String httpPath, String mjpegPath)
            throws ClientWsJsonSerializationException {
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_STREAM_STARTED);
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("ok", true);
        payload.put("camera_id", cameraId);
        payload.put("max_fps", maxFps);
        if (httpPath != null && !httpPath.isBlank()) {
            payload.put("http_path", httpPath);
        }
        if (mjpegPath != null && !mjpegPath.isBlank()) {
            payload.put("mjpeg_path", mjpegPath);
        }
        root.set("payload", payload);
        return WsOutboundJson.writeJson(root);
    }

    public String buildStreamStoppedJson(int cameraId) throws ClientWsJsonSerializationException {
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_STREAM_STOPPED);
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("ok", true);
        payload.put("camera_id", cameraId);
        root.set("payload", payload);
        return WsOutboundJson.writeJson(root);
    }

    public String buildLightBrightnessAckJson(
            JsonNode requestRoot,
            Map<String, Integer> brightnessByEndpoint,
            int defaultBrightnessPercent
    ) throws ClientWsJsonSerializationException {
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_LIGHT_BRIGHTNESS_ACK);
        root.put("protocol_version", cfg.protocolVersion());
        WsOutboundJson.copyRequestMessageId(root, requestRoot);
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("ok", true);
        payload.put("default_brightness_percent", defaultBrightnessPercent);
        payload.put("brightness_percent", defaultBrightnessPercent);
        ObjectNode endpoints = WsOutboundJson.JSON.createObjectNode();
        for (var e : brightnessByEndpoint.entrySet()) {
            endpoints.put(e.getKey(), e.getValue());
        }
        payload.set("endpoints", endpoints);
        root.set("payload", payload);
        return WsOutboundJson.writeJson(root);
    }

    public String buildReferenceBundleAckJson(JsonNode requestRoot) throws ClientWsJsonSerializationException {
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_REFERENCE_BUNDLE_ACK);
        root.put("protocol_version", cfg.protocolVersion());
        WsOutboundJson.copyRequestMessageId(root, requestRoot);
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("ok", true);
        root.set("payload", payload);
        return WsOutboundJson.writeJson(root);
    }

    public String buildFpZonesAckJson(JsonNode requestRoot, boolean ok) throws ClientWsJsonSerializationException {
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_FP_ZONES_ACK);
        root.put("protocol_version", cfg.protocolVersion());
        WsOutboundJson.copyRequestMessageId(root, requestRoot);
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("ok", ok);
        payload.put("server_ts_ms", System.currentTimeMillis());
        root.set("payload", payload);
        return WsOutboundJson.writeJson(root);
    }

    public String buildActiveReferenceViewAckJson(JsonNode requestRoot, int viewIndex)
            throws ClientWsJsonSerializationException {
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_ACTIVE_REFERENCE_VIEW_ACK);
        root.put("protocol_version", cfg.protocolVersion());
        WsOutboundJson.copyRequestMessageId(root, requestRoot);
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("ok", true);
        payload.put("view_index", viewIndex);
        payload.put("server_ts_ms", System.currentTimeMillis());
        root.set("payload", payload);
        return WsOutboundJson.writeJson(root);
    }

    public String buildSessionStateJson(ClientWsSessionState state) throws ClientWsJsonSerializationException {
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_STATE);
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("session_state", state.name());
        payload.put("server_ts_ms", System.currentTimeMillis());
        root.set("payload", payload);
        return WsOutboundJson.writeJson(root);
    }

    public String buildPlcFinsTrafficJson(PlcFinsTrafficEvent event) throws ClientWsJsonSerializationException {
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_PLC_FINS_TRAFFIC);
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("direction", event.direction());
        payload.put("operation", event.operation());
        if (event.signal() != null && !event.signal().isBlank()) {
            payload.put("signal", event.signal());
        }
        payload.put("area", event.area());
        payload.put("address", event.address());
        if (event.value() != null) {
            payload.set("value", WsOutboundJson.JSON.valueToTree(event.value()));
        }
        payload.put("hex_frame", event.hexFrame() == null ? "" : event.hexFrame());
        if (event.sid() != null) {
            payload.put("sid", event.sid());
        }
        if (event.endCode() != null) {
            payload.put("end_code", event.endCode());
        }
        payload.put("ok", event.ok());
        if (event.error() != null && !event.error().isBlank()) {
            payload.put("error", event.error());
        }
        payload.put("server_ts_ms", event.serverTsMs());
        root.set("payload", payload);
        return WsOutboundJson.writeJson(root);
    }

    public String buildErrorJson(String code, String message) throws ClientWsJsonSerializationException {
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_ERROR);
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("code", code == null ? "error" : code);
        payload.put("message", message == null ? "" : WsTextUtil.truncate(message, 800));
        root.set("payload", payload);
        return WsOutboundJson.writeJson(root);
    }
}
