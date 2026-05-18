package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessScale;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;

/**
 * Единая яркость вспышки 0…100% для всех LightServer (COM 0…100, MV-LE 0…255).
 */
public final class LightSettingsHttpController implements HttpController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LightTriggerClient lightClient;

    public LightSettingsHttpController(LightTriggerClient lightClient) {
        this.lightClient = lightClient;
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        if (lightClient == null) {
            HttpResponses.sendJsonError(ctx, 503, "light_servers disabled");
            return;
        }
        String method = ctx.method();
        if ("GET".equalsIgnoreCase(method)) {
            handleGet(ctx);
            return;
        }
        if ("PUT".equalsIgnoreCase(method)) {
            handlePut(ctx);
            return;
        }
        HttpResponses.methodNotAllowed(ctx);
    }

    private void handleGet(HttpRequestContext ctx) throws IOException {
        int p = lightClient.brightnessPercent();
        ObjectNode root = JSON.createObjectNode();
        root.put("brightness_percent", p);
        root.put("com_controller_percent", LightBrightnessScale.toComControllerPercent(p));
        root.put("mv_le_brightness", LightBrightnessScale.toMvLeBrightness(p));
        root.put("scale", "0-100 unified; COM uses percent; MV-LE maps to 0-255");
        HttpResponses.sendJson(ctx, 200, root);
    }

    private void handlePut(HttpRequestContext ctx) throws IOException {
        byte[] raw = ctx.readBody();
        if (raw.length == 0) {
            HttpResponses.sendJsonError(ctx, 400, "body required: brightness_percent");
            return;
        }
        Map<String, Object> body = JSON.readValue(raw, new TypeReference<>() {
        });
        Object v = body.get("brightness_percent");
        if (v == null) {
            HttpResponses.sendJsonError(ctx, 400, "brightness_percent required (0..100)");
            return;
        }
        int percent;
        if (v instanceof Number n) {
            percent = n.intValue();
        } else {
            try {
                percent = Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                HttpResponses.sendJsonError(ctx, 400, "brightness_percent must be integer 0..100");
                return;
            }
        }
        lightClient.setBrightnessPercent(percent);
        ObjectNode ok = JSON.createObjectNode();
        ok.put("ok", true);
        ok.put("brightness_percent", lightClient.brightnessPercent());
        HttpResponses.sendJson(ctx, 200, ok);
    }
}
