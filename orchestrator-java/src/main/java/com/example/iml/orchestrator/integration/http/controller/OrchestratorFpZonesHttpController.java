package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.clientapi.KopcheniHttpProxy;

import java.io.IOException;

/**
 * {@code /api/orchestrator/fp-zones} → прокси к FastAPI analisSurface ({@code /fp-zones}).
 */
public final class OrchestratorFpZonesHttpController implements HttpController {

    private final String analisSurfaceBaseUrl;

    public OrchestratorFpZonesHttpController(String analisSurfaceBaseUrl) {
        this.analisSurfaceBaseUrl = analisSurfaceBaseUrl == null ? "" : analisSurfaceBaseUrl.trim();
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        if (analisSurfaceBaseUrl.isEmpty()) {
            HttpResponses.sendJsonError(ctx, 503, "python_detector.base_url not configured");
            return;
        }
        String path = ctx.path();
        String target = path.replaceFirst("^/api/orchestrator", "");
        if (target.isEmpty()) {
            target = "/";
        }
        KopcheniHttpProxy.forward(ctx.exchange(), analisSurfaceBaseUrl, target);
    }
}
