package com.example.iml.orchestrator.integration.clientws.outbound;

import com.example.iml.orchestrator.integration.clientws.exception.ClientWsJsonSerializationException;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsSendFailedException;
import com.example.iml.orchestrator.integration.clientws.outbound.messages.ControlOutboundJson;
import com.example.iml.orchestrator.integration.clientws.protocol.WsMessageTypes;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;

import java.util.function.Supplier;

/** Control / ack / state / error outbound WebSocket sends. */
final class WsControlOutboundMessenger {

    private final Logger log;
    private final Supplier<ClientWsSessionState> sessionState;
    private final ControlOutboundJson controlJson;

    WsControlOutboundMessenger(
            Logger log,
            ControlOutboundJson controlJson,
            Supplier<ClientWsSessionState> sessionState
    ) {
        this.log = log;
        this.controlJson = controlJson;
        this.sessionState = sessionState;
    }

    void sendHello(WebSocket conn) {
        try {
            WsOutboundJson.sendRaw(conn, controlJson.buildHelloJson(), WsMessageTypes.SERVER_HELLO);
            log.info("client_ws sent type={} session_state={}", WsMessageTypes.SERVER_HELLO, sessionState.get());
        } catch (ClientWsJsonSerializationException e) {
            log.warn("client_ws hello failed: {}", e.getMessage());
        } catch (ClientWsSendFailedException e) {
            log.warn("client_ws hello send failed: {}", e.getMessage());
        }
    }

    void sendStreamStarted(WebSocket conn, int cameraId, int maxFps, String httpPath, String mjpegPath) {
        try {
            WsOutboundJson.sendRaw(
                    conn,
                    controlJson.buildStreamStartedJson(cameraId, maxFps, httpPath, mjpegPath),
                    WsMessageTypes.SERVER_STREAM_STARTED
            );
            log.info("client_ws sent type={} camera_id={} max_fps={}", WsMessageTypes.SERVER_STREAM_STARTED, cameraId, maxFps);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws stream_started send failed: {}", e.getMessage());
        }
    }

    void sendStreamStopped(WebSocket conn, int cameraId) {
        try {
            WsOutboundJson.sendRaw(conn, controlJson.buildStreamStoppedJson(cameraId), WsMessageTypes.SERVER_STREAM_STOPPED);
            log.info("client_ws sent type={} camera_id={}", WsMessageTypes.SERVER_STREAM_STOPPED, cameraId);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws stream_stopped send failed: {}", e.getMessage());
        }
    }

    void sendLightBrightnessAck(
            WebSocket conn,
            JsonNode requestRoot,
            java.util.Map<String, Integer> brightnessByEndpoint,
            int defaultBrightnessPercent
    ) {
        try {
            WsOutboundJson.sendRaw(
                    conn,
                    controlJson.buildLightBrightnessAckJson(requestRoot, brightnessByEndpoint, defaultBrightnessPercent),
                    WsMessageTypes.SERVER_LIGHT_BRIGHTNESS_ACK
            );
            log.info("client_ws sent type={} brightness={}", WsMessageTypes.SERVER_LIGHT_BRIGHTNESS_ACK, brightnessByEndpoint);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws light_brightness_ack send failed: {}", e.getMessage());
        }
    }

    void sendReferenceBundleAck(WebSocket conn, JsonNode requestRoot) {
        try {
            WsOutboundJson.sendRaw(
                    conn,
                    controlJson.buildReferenceBundleAckJson(requestRoot),
                    WsMessageTypes.SERVER_REFERENCE_BUNDLE_ACK
            );
            log.info("client_ws sent type={}", WsMessageTypes.SERVER_REFERENCE_BUNDLE_ACK);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws ack send failed: {}", e.getMessage());
        }
    }

    void sendFpZonesAck(WebSocket conn, JsonNode requestRoot, boolean ok) {
        try {
            WsOutboundJson.sendRaw(conn, controlJson.buildFpZonesAckJson(requestRoot, ok), WsMessageTypes.SERVER_FP_ZONES_ACK);
            log.info("client_ws sent type={} ok={}", WsMessageTypes.SERVER_FP_ZONES_ACK, ok);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws fp_zones_ack send failed: {}", e.getMessage());
        }
    }

    void sendActiveReferenceViewAck(WebSocket conn, JsonNode requestRoot, int viewIndex) {
        try {
            WsOutboundJson.sendRaw(
                    conn,
                    controlJson.buildActiveReferenceViewAckJson(requestRoot, viewIndex),
                    WsMessageTypes.SERVER_ACTIVE_REFERENCE_VIEW_ACK
            );
            log.info("client_ws sent type={} view_index={}", WsMessageTypes.SERVER_ACTIVE_REFERENCE_VIEW_ACK, viewIndex);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws active_reference_view_ack send failed: {}", e.getMessage());
        }
    }

    void sendSessionState(WebSocket conn, ClientWsSessionState state) {
        try {
            WsOutboundJson.sendRaw(conn, controlJson.buildSessionStateJson(state), WsMessageTypes.SERVER_STATE);
            log.info("client_ws sent type={} session_state={}", WsMessageTypes.SERVER_STATE, state);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws state send failed: {}", e.getMessage());
        }
    }

    void sendPlcFinsTraffic(WebSocket conn, com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent event) {
        if (event == null) {
            return;
        }
        try {
            WsOutboundJson.sendRaw(
                    conn,
                    controlJson.buildPlcFinsTrafficJson(event),
                    WsMessageTypes.SERVER_PLC_FINS_TRAFFIC
            );
            log.debug(
                    "client_ws sent type={} direction={} op={} address={}",
                    WsMessageTypes.SERVER_PLC_FINS_TRAFFIC,
                    event.direction(),
                    event.operation(),
                    event.address()
            );
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.debug("client_ws plc_fins_traffic send failed: {}", e.getMessage());
        }
    }

    void sendError(WebSocket conn, String code, String message) {
        try {
            WsOutboundJson.sendRaw(conn, controlJson.buildErrorJson(code, message), WsMessageTypes.SERVER_ERROR);
            log.info("client_ws sent type={} code={}", WsMessageTypes.SERVER_ERROR, code);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws error send failed: {}", e.getMessage());
        }
    }
}
