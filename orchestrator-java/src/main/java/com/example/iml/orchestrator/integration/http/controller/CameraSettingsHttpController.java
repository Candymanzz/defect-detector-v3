package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.camera.CameraSettingsService;
import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;

public final class CameraSettingsHttpController implements HttpController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CameraSettingsService settingsService;

    public CameraSettingsHttpController(CameraSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        String path = ctx.path();
        String method = ctx.method();
        Integer cameraId = parseCameraId(path);
        if (cameraId == null) {
            HttpResponses.notFound(ctx);
            return;
        }
        if ("GET".equalsIgnoreCase(method)) {
            getSettings(ctx, cameraId);
            return;
        }
        if ("PATCH".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
            patchSettings(ctx, cameraId);
            return;
        }
        HttpResponses.methodNotAllowed(ctx);
    }

    private void getSettings(HttpRequestContext ctx, int cameraId) throws IOException {
        if (settingsService == null) {
            HttpResponses.sendJsonError(ctx, 503, "camera workers not ready");
            return;
        }
        try {
            Map<String, Object> settings = settingsService.getSettings(cameraId);
            HttpResponses.sendJson(ctx, 200, JSON.valueToTree(settings));
        } catch (IllegalArgumentException e) {
            HttpResponses.sendJsonError(ctx, 404, e.getMessage());
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 502, "camera settings read failed: " + e.getMessage());
        }
    }

    private void patchSettings(HttpRequestContext ctx, int cameraId) throws IOException {
        if (settingsService == null) {
            HttpResponses.sendJsonError(ctx, 503, "camera workers not ready");
            return;
        }
        byte[] body = ctx.readBody();
        Map<String, Object> raw = Map.of();
        if (body != null && body.length > 0) {
            try {
                raw = JSON.readValue(body, new TypeReference<>() {});
            } catch (Exception e) {
                HttpResponses.sendJsonError(ctx, 400, "invalid JSON body: " + e.getMessage());
                return;
            }
        }
        Map<String, Object> patch = CameraSettingsService.parsePatchBody(raw);
        if (patch.isEmpty()) {
            HttpResponses.sendJsonError(ctx, 400,
                    "supported fields: exposure_us, gain_db, gamma, black_level, balance_ratio_red, balance_ratio_blue, capture_trigger_mode, frame_timeout_ms");
            return;
        }
        try {
            Map<String, Object> settings = settingsService.patchSettings(cameraId, patch);
            ObjectNode root = JSON.valueToTree(settings);
            root.put("ok", true);
            HttpResponses.sendJson(ctx, 200, root);
        } catch (IllegalArgumentException e) {
            HttpResponses.sendJsonError(ctx, 404, e.getMessage());
        } catch (IllegalStateException e) {
            HttpResponses.sendJsonError(ctx, 409, e.getMessage());
        } catch (Exception e) {
            HttpResponses.sendJsonError(ctx, 502, "camera settings update failed: " + e.getMessage());
        }
    }

    private static Integer parseCameraId(String path) {
        if (path == null || !path.startsWith("/api/camera/") || !path.endsWith("/settings")) {
            return null;
        }
        String[] parts = path.split("/");
        if (parts.length < 5) {
            return null;
        }
        try {
            return Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
