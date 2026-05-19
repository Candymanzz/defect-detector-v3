package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessCommands;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessScale;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;

/**
 * Единая яркость вспышки 0…100% для всех LightServer (COM 0…100, MV-LE 0…255).
 */
public final class LightSettingsHttpController implements HttpController {

    private static final Logger LOG = LogManager.getLogger(LightSettingsHttpController.class);
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
        if ("PUT".equalsIgnoreCase(method) || "POST".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            handleSet(ctx);
            return;
        }
        HttpResponses.methodNotAllowed(ctx);
    }

    private void handleGet(HttpRequestContext ctx) throws IOException {
        Integer fromQuery = LightBrightnessCommands.parseUnifiedPercentFromQuery(ctx.query());
        if (fromQuery != null) {
            lightClient.setBrightnessPercent(fromQuery);
            LOG.info("light brightness set to {}% via GET query", lightClient.brightnessPercent());
        }
        sendCurrent(ctx);
    }

    private void handleSet(HttpRequestContext ctx) throws IOException {
        Integer percent = LightBrightnessCommands.parseUnifiedPercentFromQuery(ctx.query());
        byte[] raw = ctx.readBody();
        if (percent == null && raw.length > 0) {
            percent = LightBrightnessCommands.parseUnifiedPercentFromHttpBody(raw);
        }
        if (percent == null && raw.length > 0) {
            Map<String, Object> body = JSON.readValue(raw, new TypeReference<>() {
            });
            percent = LightBrightnessCommands.parseUnifiedPercentFromMap(body);
        }
        if (percent == null) {
            HttpResponses.sendJsonError(ctx, 400, "brightness_percent required (0..100), query or JSON body");
            return;
        }
        int before = lightClient.brightnessPercent();
        lightClient.setBrightnessPercent(percent);
        LOG.info("light brightness updated {}% -> {}% via HTTP {} {}",
                before, lightClient.brightnessPercent(), ctx.method(), ctx.path());
        ObjectNode ok = JSON.createObjectNode();
        ok.put("ok", true);
        ok.put("brightness_percent", lightClient.brightnessPercent());
        ok.put("com_controller_percent", LightBrightnessScale.toComControllerPercent(lightClient.brightnessPercent()));
        ok.put("mv_le_brightness", LightBrightnessScale.toMvLeBrightness(lightClient.brightnessPercent()));
        HttpResponses.sendJson(ctx, 200, ok);
    }

    private void sendCurrent(HttpRequestContext ctx) throws IOException {
        int p = lightClient.brightnessPercent();
        ObjectNode root = JSON.createObjectNode();
        root.put("brightness_percent", p);
        root.put("com_controller_percent", LightBrightnessScale.toComControllerPercent(p));
        root.put("mv_le_brightness", LightBrightnessScale.toMvLeBrightness(p));
        root.put("scale", "0-100 unified; COM uses percent; MV-LE maps to 0-255");
        HttpResponses.sendJson(ctx, 200, root);
    }
}
