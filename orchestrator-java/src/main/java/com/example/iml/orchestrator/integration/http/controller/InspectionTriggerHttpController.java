package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBusHolder;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * Manual inspection trigger for UI controls.
 */
public final class InspectionTriggerHttpController implements HttpController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final InspectionTriggerBusHolder busHolder;

    public InspectionTriggerHttpController(InspectionTriggerBusHolder busHolder) {
        this.busHolder = busHolder;
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        if (!"POST".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }

        InspectionTriggerBus bus = busHolder == null ? null : busHolder.get();
        if (bus == null) {
            HttpResponses.sendJsonError(ctx, 503, "inspection trigger runtime is not ready");
            return;
        }

        int cameraId = readCameraId(ctx.readBody());
        if (!bus.hasCamera(cameraId)) {
            HttpResponses.sendJsonError(ctx, 404, "unknown camera_id=" + cameraId);
            return;
        }

        boolean queued = bus.publish(InspectionTriggerEvent.of(cameraId, "ui_http"));
        if (!queued) {
            HttpResponses.sendJsonError(ctx, 503, "inspection trigger queue is full");
            return;
        }

        ObjectNode root = JSON.createObjectNode();
        root.put("ok", true);
        root.put("cameraId", cameraId);
        root.put("source", "ui_http");
        HttpResponses.sendJson(ctx, 202, root);
    }

    private static int readCameraId(byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            return 0;
        }
        JsonNode root = JSON.readTree(body);
        if (root.has("cameraId")) {
            return root.path("cameraId").asInt(0);
        }
        return root.path("camera_id").asInt(0);
    }
}
