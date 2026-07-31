package com.example.iml.orchestrator.integration.http.controller.clientapi;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;

public final class ClientApiLineDirectionRoutes {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ClientApiMount clientApi;

    public ClientApiLineDirectionRoutes(ClientApiMount clientApi) {
        this.clientApi = clientApi;
    }

    public void handle(HttpRequestContext ctx) throws IOException {
        HttpResponses.corsJson(ctx.exchange());
        ManualLineDirectionService lineDirection = clientApi.manualLineDirection();
        if (lineDirection == null) {
            HttpResponses.sendJsonError(ctx, 503, "line direction not configured");
            return;
        }
        String m = ctx.method();
        if ("GET".equalsIgnoreCase(m)) {
            ObjectNode root = JSON.createObjectNode();
            root.put("direction", lineDirection.wireValue());
            root.put("source", "manual");
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
            return;
        }
        if ("PUT".equalsIgnoreCase(m)) {
            byte[] raw = ctx.readBody();
            if (raw.length == 0) {
                HttpResponses.sendJsonError(ctx, 400, "body.direction required (forward|reverse)");
                return;
            }
            Map<String, Object> body = JSON.readValue(raw, new TypeReference<>() {
            });
            Object directionRaw = body.get("direction");
            if (directionRaw == null) {
                HttpResponses.sendJsonError(ctx, 400, "body.direction required (forward|reverse)");
                return;
            }
            try {
                lineDirection.setFromWireValue(String.valueOf(directionRaw));
            } catch (IllegalArgumentException e) {
                HttpResponses.sendJsonError(ctx, 400, e.getMessage());
                return;
            }
            ObjectNode root = JSON.createObjectNode();
            root.put("ok", true);
            root.put("direction", lineDirection.wireValue());
            root.put("source", "manual");
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
            return;
        }
        HttpResponses.methodNotAllowed(ctx);
    }
}
