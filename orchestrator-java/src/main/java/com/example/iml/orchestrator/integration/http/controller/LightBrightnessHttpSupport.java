package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessApplyResult;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessCommands;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessScale;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessUpdate;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;

/** Brightness GET/PUT helpers for {@link LightHttpController}. */
final class LightBrightnessHttpSupport {

    private final ObjectMapper json;
    private final Logger log;
    private final LightTriggerClient lightClient;
    private final LightBrightnessStore brightnessStore;

    LightBrightnessHttpSupport(
            ObjectMapper json,
            Logger log,
            LightTriggerClient lightClient,
            LightBrightnessStore brightnessStore
    ) {
        this.json = json;
        this.log = log;
        this.lightClient = lightClient;
        this.brightnessStore = brightnessStore;
    }

    void handleGet(HttpRequestContext ctx) throws IOException {
        LightBrightnessUpdate fromQuery = LightBrightnessCommands.parseBrightnessUpdateFromQuery(ctx.query());
        if (!fromQuery.isEmpty()) {
            applyBrightnessUpdate(fromQuery);
            persistBrightnessState();
        }
        sendBrightness(ctx);
    }

    void setBrightness(HttpRequestContext ctx) throws IOException {
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
        log.info("light brightness updated via {} {} -> {}", ctx.method(), ctx.path(), lightClient.brightnessByEndpoint());
        persistBrightnessState();
        if (applyResult.hasHardwareErrors()) {
            log.warn("light brightness hardware errors: {}", String.join("; ", applyResult.hardwareErrors()));
        }
        int defaultPercent = lightClient.brightnessPercent();
        ObjectNode ok = json.createObjectNode();
        ok.put("ok", !applyResult.hasHardwareErrors());
        ok.put("hardware_applied", !applyResult.hasHardwareErrors());
        ok.put("default_brightness_percent", defaultPercent);
        ok.put("brightness_percent", defaultPercent);
        ok.set("endpoints", buildEndpointsNode());
        if (applyResult.hasHardwareErrors()) {
            ArrayNode errors = json.createArrayNode();
            for (String error : applyResult.hardwareErrors()) {
                errors.add(error);
            }
            ok.set("hardware_errors", errors);
        }
        HttpResponses.sendJson(ctx, applyResult.hasHardwareErrors() ? 502 : 200, ok);
    }

    void persistBrightnessState() {
        if (brightnessStore == null) {
            return;
        }
        try {
            brightnessStore.saveFromClient(lightClient);
        } catch (IOException e) {
            log.warn("light brightness store save failed: {}", e.getMessage());
        }
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

    private void sendBrightness(HttpRequestContext ctx) throws IOException {
        int defaultPercent = lightClient.brightnessPercent();
        ObjectNode root = json.createObjectNode();
        root.put("default_brightness_percent", defaultPercent);
        root.put("brightness_percent", defaultPercent);
        root.set("endpoints", buildEndpointsNode());
        HttpResponses.sendJson(ctx, 200, root);
    }

    private ArrayNode buildEndpointsNode() {
        ArrayNode arr = json.createArrayNode();
        for (String id : lightClient.endpointIds()) {
            int p = lightClient.brightnessPercent(id);
            ObjectNode ep = json.createObjectNode();
            ep.put("id", id);
            ep.put("brightness_percent", p);
            ep.put("mv_le_brightness", LightBrightnessScale.toMvLeBrightness(p));
            ArrayNode cameraIds = json.createArrayNode();
            for (int cameraId : lightClient.cameraIds(id)) {
                cameraIds.add(cameraId);
            }
            ep.set("camera_ids", cameraIds);
            arr.add(ep);
        }
        return arr;
    }
}
