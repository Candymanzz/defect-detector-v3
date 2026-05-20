package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessCommands;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Прокси legacy-путей LightServer на :8099 (через Vite {@code /api/*}): обновляет runtime-яркость
 * оркестратора и пересылает тело на COM / MV-LE.
 */
public final class LightLegacyProxyHttpController implements HttpController {

    private final LightTriggerClient lightClient;
    private final URI mvLePostUri;
    private final URI comTriggerUri;
    private final Duration timeout;
    private final HttpClient httpClient;

    public LightLegacyProxyHttpController(LightTriggerClient lightClient, LightServersConfig cfg) {
        this.lightClient = lightClient;
        this.mvLePostUri = resolveMvLeUri(cfg);
        this.comTriggerUri = resolveComTriggerUri(cfg);
        int timeoutMs = cfg == null ? 1500 : Math.max(100, cfg.timeoutMs());
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        if (lightClient == null) {
            HttpResponses.sendJsonError(ctx, 503, "light_servers disabled");
            return;
        }
        String path = ctx.path();
        byte[] body = ctx.readBody();
        Integer fromQuery = LightBrightnessCommands.parseUnifiedPercentFromQuery(ctx.query());
        LightBrightnessCommands.applyIfPresent(lightClient, fromQuery);
        Integer fromBody = LightBrightnessCommands.parseUnifiedPercentFromHttpBody(body);
        LightBrightnessCommands.applyIfPresent(lightClient, fromBody);

        if ("/api/light".equals(path)) {
            if (mvLePostUri == null) {
                HttpResponses.sendJsonError(ctx, 503, "MV-LE light endpoint not configured");
                return;
            }
            forward(ctx, mvLePostUri, body);
            return;
        }
        if ("/api/light/trigger-inspection".equals(path)) {
            if (comTriggerUri == null) {
                HttpResponses.sendJsonError(ctx, 503, "COM trigger-inspection endpoint not configured");
                return;
            }
            forward(ctx, comTriggerUri, body);
            return;
        }
        HttpResponses.notFound(ctx);
    }

    private void forward(HttpRequestContext ctx, URI target, byte[] body) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(target)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            ObjectNode root = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().createObjectNode();
            root.put("ok", response.statusCode() / 100 == 2);
            root.put("forwarded_to", target.toString());
            root.put("upstream_status", response.statusCode());
            root.put("brightness_percent", lightClient.brightnessPercent());
            if (response.body() != null && !response.body().isBlank()) {
                root.put("upstream_body", response.body().length() > 500
                        ? response.body().substring(0, 500) + "…"
                        : response.body());
            }
            HttpResponses.sendJson(ctx, response.statusCode() / 100 == 2 ? 200 : 502, root);
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 502, "light proxy failed: " + e.getMessage());
        }
    }

    private static URI resolveMvLeUri(LightServersConfig cfg) {
        if (cfg == null) {
            return null;
        }
        for (LightServersConfig.EndpointSpec ep : cfg.endpoints()) {
            if (ep.enabled() && ep.type() == LightServersConfig.EndpointType.MV_LE) {
                String base = ep.baseUrl().endsWith("/") ? ep.baseUrl().substring(0, ep.baseUrl().length() - 1) : ep.baseUrl();
                return URI.create(base + "/api/light");
            }
        }
        return null;
    }

    private static URI resolveComTriggerUri(LightServersConfig cfg) {
        if (cfg == null) {
            return null;
        }
        for (LightServersConfig.EndpointSpec ep : cfg.endpoints()) {
            if (ep.enabled() && ep.type() == LightServersConfig.EndpointType.TRIGGER_INSPECTION) {
                String path = ep.triggerPath().startsWith("/") ? ep.triggerPath() : "/" + ep.triggerPath();
                String base = ep.baseUrl().endsWith("/") ? ep.baseUrl().substring(0, ep.baseUrl().length() - 1) : ep.baseUrl();
                return URI.create(base + path);
            }
        }
        return null;
    }
}
