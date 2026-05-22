package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessCommands;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessScale;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessUpdate;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.lighting.LightUpstreamClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;

/**
 * HTTP API подсветки: яркость по endpoint, вспышка по триггеру, прокси LightServer.v3.
 */
public final class LightHttpController implements HttpController {

    private static final Logger LOG = LogManager.getLogger(LightHttpController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LightTriggerClient lightClient;
    private final LightUpstreamClient upstream;

    public LightHttpController(LightTriggerClient lightClient, LightServersConfig cfg) {
        this.lightClient = lightClient;
        this.upstream = cfg != null && cfg.enabled() ? new LightUpstreamClient(cfg) : null;
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        HttpResponses.notFound(ctx);
    }

    public void handleBrightness(HttpRequestContext ctx) throws IOException {
        if (!requireLight(ctx)) {
            return;
        }
        String method = ctx.method();
        if ("GET".equalsIgnoreCase(method)) {
            LightBrightnessUpdate fromQuery = LightBrightnessCommands.parseBrightnessUpdateFromQuery(ctx.query());
            if (!fromQuery.isEmpty()) {
                applyBrightnessUpdate(fromQuery);
            }
            sendBrightness(ctx);
            return;
        }
        if ("PUT".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            setBrightness(ctx);
            return;
        }
        HttpResponses.methodNotAllowed(ctx);
    }

    public void handleTrigger(HttpRequestContext ctx) throws IOException {
        if (!requireLight(ctx)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        byte[] body = ctx.readBody();
        applyBrightnessUpdate(LightBrightnessCommands.parseBrightnessUpdateFromQuery(ctx.query()));
        applyBrightnessUpdate(LightBrightnessCommands.parseBrightnessUpdate(body));
        int cameraId = 0;
        long frameId = -1L;
        String phase = "capture";
        try {
            if (body != null && body.length > 0) {
                JsonNode root = JSON.readTree(body);
                cameraId = root.path("cameraId").asInt(0);
                frameId = root.path("frameId").asLong(-1L);
                if (root.hasNonNull("phase")) {
                    phase = root.get("phase").asText("capture");
                }
            }
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 400, "invalid JSON body: " + e.getMessage());
            return;
        }
        try {
            lightClient.trigger(cameraId, frameId, phase);
            ObjectNode root = JSON.createObjectNode();
            root.put("ok", true);
            root.put("cameraId", cameraId);
            root.put("frameId", frameId);
            root.put("phase", phase);
            root.set("endpoints", buildEndpointsNode());
            HttpResponses.sendJson(ctx, 200, root);
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 502, "light trigger failed: " + e.getMessage());
        }
    }

    public void handleNetworkDevices(HttpRequestContext ctx) throws IOException {
        if (!requireUpstream(ctx)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        forwardGet(ctx, "/api/devices");
    }

    public void handleComDevices(HttpRequestContext ctx) throws IOException {
        if (!requireUpstream(ctx)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        forwardGet(ctx, "/api/com/devices");
    }

    public void handleNetworkLight(HttpRequestContext ctx) throws IOException {
        if (!requireUpstream(ctx)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        byte[] body = ctx.readBody();
        applyBrightnessUpdate(LightBrightnessCommands.parseBrightnessUpdateFromQuery(ctx.query()));
        applyBrightnessUpdate(LightBrightnessCommands.parseBrightnessUpdate(body));
        forwardPost(ctx, "/api/light", body);
    }

    public void handleComLight(HttpRequestContext ctx) throws IOException {
        if (!requireUpstream(ctx)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        byte[] body = ctx.readBody();
        applyBrightnessUpdate(LightBrightnessCommands.parseBrightnessUpdateFromQuery(ctx.query()));
        applyBrightnessUpdate(LightBrightnessCommands.parseBrightnessUpdate(body));
        forwardPost(ctx, "/api/com/light", body);
    }

    private void setBrightness(HttpRequestContext ctx) throws IOException {
        LightBrightnessUpdate fromQuery = LightBrightnessCommands.parseBrightnessUpdateFromQuery(ctx.query());
        byte[] raw = ctx.readBody();
        LightBrightnessUpdate fromBody = LightBrightnessCommands.parseBrightnessUpdate(raw);
        LightBrightnessUpdate merged = mergeUpdates(fromQuery, fromBody);
        if (merged.isEmpty()) {
            HttpResponses.sendJsonError(ctx, 400,
                    "brightness_percent and/or endpoints{id: percent} required (0..100)");
            return;
        }
        applyBrightnessUpdate(merged);
        LOG.info("light brightness updated via {} {} -> {}", ctx.method(), ctx.path(), lightClient.brightnessByEndpoint());
        ObjectNode ok = JSON.createObjectNode();
        ok.put("ok", true);
        ok.put("default_brightness_percent", lightClient.brightnessPercent());
        ok.set("endpoints", buildEndpointsNode());
        HttpResponses.sendJson(ctx, 200, ok);
    }

    private static LightBrightnessUpdate mergeUpdates(LightBrightnessUpdate a, LightBrightnessUpdate b) {
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        Map<String, Integer> per = new java.util.LinkedHashMap<>(a.perEndpoint());
        per.putAll(b.perEndpoint());
        Integer global = b.globalPercent() != null ? b.globalPercent() : a.globalPercent();
        return new LightBrightnessUpdate(global, per);
    }

    private void applyBrightnessUpdate(LightBrightnessUpdate update) {
        if (update == null || update.isEmpty()) {
            return;
        }
        LightBrightnessUpdate.apply(lightClient, update);
    }

    private void sendBrightness(HttpRequestContext ctx) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("default_brightness_percent", lightClient.brightnessPercent());
        root.set("endpoints", buildEndpointsNode());
        root.put("upstream_base_url", upstream == null ? "" : upstream.baseUrl());
        HttpResponses.sendJson(ctx, 200, root);
    }

    private ArrayNode buildEndpointsNode() {
        ArrayNode arr = JSON.createArrayNode();
        for (String id : lightClient.endpointIds()) {
            int p = lightClient.brightnessPercent(id);
            ObjectNode ep = JSON.createObjectNode();
            ep.put("id", id);
            ep.put("brightness_percent", p);
            ep.put("mv_le_brightness", LightBrightnessScale.toMvLeBrightness(p));
            arr.add(ep);
        }
        return arr;
    }

    private void forwardGet(HttpRequestContext ctx, String path) throws IOException {
        String q = ctx.query();
        String pathAndQuery = q == null || q.isBlank() ? path : path + "?" + q;
        try {
            LightUpstreamClient.UpstreamResponse resp = upstream.get(pathAndQuery);
            passthroughJson(ctx, resp);
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 502, "light upstream GET failed: " + e.getMessage());
        }
    }

    private void forwardPost(HttpRequestContext ctx, String path, byte[] body) throws IOException {
        String q = ctx.query();
        String pathAndQuery = q == null || q.isBlank() ? path : path + "?" + q;
        try {
            LightUpstreamClient.UpstreamResponse resp = upstream.post(pathAndQuery, body);
            passthroughJson(ctx, resp);
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 502, "light upstream POST failed: " + e.getMessage());
        }
    }

    private static void passthroughJson(HttpRequestContext ctx, LightUpstreamClient.UpstreamResponse resp)
            throws IOException {
        if (resp.body() != null && !resp.body().isBlank()) {
            try {
                JsonNode node = JSON.readTree(resp.body());
                HttpResponses.sendJson(ctx, resp.ok() ? 200 : resp.statusCode(), node);
                return;
            } catch (Exception ignored) {
            }
        }
        ObjectNode wrap = JSON.createObjectNode();
        wrap.put("ok", resp.ok());
        if (resp.body() != null) {
            wrap.put("body", resp.body());
        }
        HttpResponses.sendJson(ctx, resp.ok() ? 200 : resp.statusCode(), wrap);
    }

    private boolean requireLight(HttpRequestContext ctx) throws IOException {
        if (lightClient == null) {
            HttpResponses.sendJsonError(ctx, 503, "light_servers disabled");
            return false;
        }
        return true;
    }

    private boolean requireUpstream(HttpRequestContext ctx) throws IOException {
        if (!requireLight(ctx)) {
            return false;
        }
        if (upstream == null) {
            HttpResponses.sendJsonError(ctx, 503, "light_servers disabled");
            return false;
        }
        return true;
    }
}
