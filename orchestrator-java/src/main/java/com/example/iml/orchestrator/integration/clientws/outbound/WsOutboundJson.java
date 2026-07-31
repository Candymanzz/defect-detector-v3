package com.example.iml.orchestrator.integration.clientws.outbound;

import com.example.iml.orchestrator.integration.clientws.exception.ClientWsJsonSerializationException;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsSendFailedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.java_websocket.WebSocket;

import java.util.UUID;

/**
 * Shared JSON write / WebSocket send helpers for outbound server.* messages.
 */
public final class WsOutboundJson {

    public static final ObjectMapper JSON = new ObjectMapper();

    private WsOutboundJson() {
    }

    public static void copyRequestMessageId(ObjectNode root, JsonNode requestEnvelope) {
        if (requestEnvelope != null && requestEnvelope.hasNonNull("message_id")) {
            String mid = requestEnvelope.get("message_id").asText("").trim();
            if (!mid.isEmpty()) {
                root.put("message_id", mid);
                return;
            }
        }
        root.put("message_id", UUID.randomUUID().toString());
    }

    public static String writeJson(ObjectNode root) throws ClientWsJsonSerializationException {
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new ClientWsJsonSerializationException("json write failed", e);
        }
    }

    public static void sendRaw(WebSocket conn, String json, String type) throws ClientWsSendFailedException {
        try {
            conn.send(json);
        } catch (RuntimeException e) {
            throw new ClientWsSendFailedException(type, e);
        }
    }
}
