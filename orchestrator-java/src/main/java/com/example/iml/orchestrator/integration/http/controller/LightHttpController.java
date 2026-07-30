package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessApplyResult;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessCommands;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessScale;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessUpdate;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;

/**
 * HTTP API подсветки: яркость по endpoint и режим constant/interval.
 */
public final class LightHttpController implements HttpController {

    private static final Logger LOG = LogManager.getLogger(LightHttpController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LightTriggerClient lightClient;
    private final LightBrightnessStore brightnessStore;

    public LightHttpController(LightTriggerClient lightClient, LightServersConfig cfg) {
        this(lightClient, cfg, null);
    }

    public LightHttpController(
            LightTriggerClient lightClient,
            LightServersConfig cfg,
            LightBrightnessStore brightnessStore
    ) {
        this.lightClient = lightClient;
        this.brightnessStore = brightnessStore;
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
                persistBrightnessState();
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

    public void handleMode(HttpRequestContext ctx) throws IOException {
        if (!requireLight(ctx)) {
            return;
        }
        if ("GET".equalsIgnoreCase(ctx.method())) {
            sendMode(ctx);
            return;
        }
        if ("PUT".equalsIgnoreCase(ctx.method()) || "POST".equalsIgnoreCase(ctx.method())) {
            setMode(ctx);
            return;
        }
        HttpResponses.methodNotAllowed(ctx);
    }

    private void setMode(HttpRequestContext ctx) throws IOException {
        try {
            var root = JSON.readTree(ctx.readBody());
            boolean constant = root.path("constant").asBoolean(false);
            if (root.has("mode")) {
                String mode = root.path("mode").asText("").trim().toLowerCase();
                if ("constant".equals(mode)) {
                    constant = true;
                } else if ("interval".equals(mode)) {
                    constant = false;
                }
            }
            lightClient.setConstantFlashMode(constant);
            persistBrightnessState();
            sendMode(ctx);
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 400, "invalid JSON body: " + e.getMessage());
        }
    }

    private void sendMode(HttpRequestContext ctx) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("constant", lightClient.isConstantFlashMode());
        root.put("mode", lightClient.isConstantFlashMode() ? "constant" : "interval");
        HttpResponses.sendJson(ctx, 200, root);
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
        LightBrightnessApplyResult applyResult = applyBrightnessUpdate(merged);
        LOG.info("light brightness updated via {} {} -> {}", ctx.method(), ctx.path(), lightClient.brightnessByEndpoint());
        persistBrightnessState();
        if (applyResult.hasHardwareErrors()) {
            LOG.warn("light brightness hardware errors: {}", String.join("; ", applyResult.hardwareErrors()));
        }
        int defaultPercent = lightClient.brightnessPercent();
        ObjectNode ok = JSON.createObjectNode();
        ok.put("ok", !applyResult.hasHardwareErrors());
        ok.put("hardware_applied", !applyResult.hasHardwareErrors());
        ok.put("default_brightness_percent", defaultPercent);
        ok.put("brightness_percent", defaultPercent);
        ok.set("endpoints", buildEndpointsNode());
        if (applyResult.hasHardwareErrors()) {
            ArrayNode errors = JSON.createArrayNode();
            for (String error : applyResult.hardwareErrors()) {
                errors.add(error);
            }
            ok.set("hardware_errors", errors);
        }
        HttpResponses.sendJson(ctx, applyResult.hasHardwareErrors() ? 502 : 200, ok);
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

    private LightBrightnessApplyResult applyBrightnessUpdate(LightBrightnessUpdate update) {
        if (update == null || update.isEmpty()) {
            return LightBrightnessApplyResult.none();
        }
        return LightBrightnessUpdate.apply(lightClient, update);
    }

    private void persistBrightnessState() {
        if (brightnessStore == null) {
            return;
        }
        try {
            brightnessStore.saveFromClient(lightClient);
        } catch (IOException e) {
            LOG.warn("light brightness store save failed: {}", e.getMessage());
        }
    }

    private void sendBrightness(HttpRequestContext ctx) throws IOException {
        int defaultPercent = lightClient.brightnessPercent();
        ObjectNode root = JSON.createObjectNode();
        root.put("default_brightness_percent", defaultPercent);
        root.put("brightness_percent", defaultPercent);
        root.set("endpoints", buildEndpointsNode());
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
            ArrayNode cameraIds = JSON.createArrayNode();
            for (int cameraId : lightClient.cameraIds(id)) {
                cameraIds.add(cameraId);
            }
            ep.set("camera_ids", cameraIds);
            arr.add(ep);
        }
        return arr;
    }

    private boolean requireLight(HttpRequestContext ctx) throws IOException {
        if (lightClient == null || !lightClient.isEnabled()) {
            HttpResponses.sendJsonError(ctx, 503, "light_servers disabled");
            return false;
        }
        return true;
    }
}
