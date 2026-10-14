package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * {@code POST /api/client-log} — пачка событий браузера: {@code {"events":[{...},...]}}.
 * Каждое событие пишется одной строкой JSON в {@code <сессия>/frontend/client.log}; время события
 * ({@code t}) проставляет сам браузер, чтобы не терять точность из-за батчинга.
 */
public final class ClientLogHttpController implements HttpController {

    private static final Logger CLIENT_LOG = LogManager.getLogger("com.example.iml.client");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 512 * 1024;
    private static final int MAX_EVENTS_PER_BATCH = 500;

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        byte[] body = ctx.readBody();
        if (body.length > MAX_BODY_BYTES) {
            HttpResponses.sendJsonError(ctx, 413, "client-log batch too large");
            return;
        }
        JsonNode events;
        try {
            events = JSON.readTree(body).path("events");
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 400, "invalid json");
            return;
        }
        if (events.isArray()) {
            int n = 0;
            for (JsonNode event : events) {
                if (++n > MAX_EVENTS_PER_BATCH) {
                    break;
                }
                CLIENT_LOG.info(event.toString());
            }
        }
        HttpResponses.corsJson(ctx.exchange());
        ctx.exchange().sendResponseHeaders(204, -1);
        ctx.exchange().close();
    }
}
