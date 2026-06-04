package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.clientapi.KopcheniHttpProxy;
import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;

import java.io.IOException;

/**
 * {@code /api/orchestrator/analysis-settings} -> proxy to FastAPI analisSurface
 * ({@code /analysis-settings}).
 */
public final class OrchestratorAnalysisSettingsHttpController implements HttpController {

    private final String analisSurfaceBaseUrl;

    public OrchestratorAnalysisSettingsHttpController(String analisSurfaceBaseUrl) {
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
