package com.example.iml.orchestrator.integration.clientws.routing;

import com.example.iml.orchestrator.integration.clientws.application.ClientWsApplicationContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.java_websocket.WebSocket;

/**
 * Контекст входящего WebSocket-сообщения.
 */
public final class WsMessageContext {

    private final WebSocket connection;
    private final JsonNode envelope;
    private final String messageType;
    private final ClientWsApplicationContext application;

    public WsMessageContext(
            WebSocket connection,
            JsonNode envelope,
            String messageType,
            ClientWsApplicationContext application
    ) {
        this.connection = connection;
        this.envelope = envelope;
        this.messageType = messageType;
        this.application = application;
    }

    public WebSocket connection() {
        return connection;
    }

    public JsonNode envelope() {
        return envelope;
    }

    public String messageType() {
        return messageType;
    }

    public ClientWsApplicationContext application() {
        return application;
    }
}
