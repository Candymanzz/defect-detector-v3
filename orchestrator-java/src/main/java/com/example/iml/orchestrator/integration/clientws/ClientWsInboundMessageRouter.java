package com.example.iml.orchestrator.integration.clientws;

import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger;
import com.example.iml.orchestrator.integration.clientws.routing.WsFrontController;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;

/**
 * Parses inbound JSON and dispatches to {@link WsFrontController}.
 */
final class ClientWsInboundMessageRouter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Logger log;
    private final WsFrontController frontController;
    private final WsOutboundMessenger outbound;

    ClientWsInboundMessageRouter(Logger log, WsFrontController frontController, WsOutboundMessenger outbound) {
        this.log = log;
        this.frontController = frontController;
        this.outbound = outbound;
    }

    void onMessage(WebSocket conn, String message) {
        try {
            JsonNode root = JSON.readTree(message);
            String type = root.path("type").asText("?");
            frontController.dispatch(conn, root, type);
        } catch (JsonProcessingException e) {
            log.warn("client_ws message parse: {}", e.getMessage());
            outbound.sendError(
                    conn,
                    "parse_error",
                    e.getOriginalMessage() == null ? "invalid json" : e.getOriginalMessage()
            );
        }
    }
}
