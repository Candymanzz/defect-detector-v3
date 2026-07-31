package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * HTTP API подсветки: яркость по endpoint и режим constant/interval.
 */
public final class LightHttpController implements HttpController {

    private static final Logger LOG = LogManager.getLogger(LightHttpController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LightTriggerClient lightClient;
    private final LightBrightnessHttpSupport brightness;

    public LightHttpController(
            LightTriggerClient lightClient,
            LightBrightnessStore brightnessStore
    ) {
        this.lightClient = lightClient;
        this.brightness = new LightBrightnessHttpSupport(JSON, LOG, lightClient, brightnessStore);
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
            brightness.handleGet(ctx);
            return;
        }
        if ("PUT".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            brightness.setBrightness(ctx);
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
            brightness.persistBrightnessState();
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

    private boolean requireLight(HttpRequestContext ctx) throws IOException {
        if (lightClient == null || !lightClient.isEnabled()) {
            HttpResponses.sendJsonError(ctx, 503, "light_servers disabled");
            return false;
        }
        return true;
    }
}
