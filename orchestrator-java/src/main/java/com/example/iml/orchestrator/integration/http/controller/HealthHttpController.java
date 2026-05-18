package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class HealthHttpController implements HttpController {

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        HttpResponses.send(ctx, 200, "text/plain", "ok\n".getBytes(StandardCharsets.UTF_8));
    }
}
