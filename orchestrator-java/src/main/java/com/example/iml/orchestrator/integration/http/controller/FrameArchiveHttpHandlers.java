package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpJsonCameraIds;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Settings / delete handlers for frame-archive HTTP routes. */
final class FrameArchiveHttpHandlers {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FrameArchiveService frameArchive;
    private final FrameArchiveHistoryHandler history;

    FrameArchiveHttpHandlers(FrameArchiveService frameArchive) {
        this.frameArchive = frameArchive;
        this.history = new FrameArchiveHistoryHandler(frameArchive);
    }

    void handleSettings(HttpRequestContext ctx) throws IOException {
        if (frameArchive == null || !frameArchive.enabled()) {
            HttpResponses.sendJsonError(ctx, 503, "frame archive disabled");
            return;
        }
        HttpResponses.corsJson(ctx.exchange());
        String method = ctx.method();
        if ("GET".equalsIgnoreCase(method)) {
            ObjectNode root = JSON.createObjectNode();
            root.put("enabled", true);
            root.put("directory", frameArchive.directory().toString());
            root.put("max_frames_per_camera", frameArchive.maxFramesPerCamera());
            root.put("max_allowed_frames_per_camera", frameArchive.maxAllowedFramesPerCamera());
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
            return;
        }
        if ("PUT".equalsIgnoreCase(method)) {
            byte[] raw = ctx.readBody();
            if (raw.length == 0) {
                HttpResponses.sendJsonError(ctx, 400, "body.max_frames_per_camera required");
                return;
            }
            Map<String, Object> body = JSON.readValue(raw, new TypeReference<>() {});
            Object maxFramesRaw = body.get("max_frames_per_camera");
            if (maxFramesRaw == null) {
                HttpResponses.sendJsonError(ctx, 400, "body.max_frames_per_camera required");
                return;
            }
            int maxFrames = maxFramesRaw instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(maxFramesRaw).trim());
            frameArchive.setMaxFramesPerCamera(maxFrames);
            ObjectNode root = JSON.createObjectNode();
            root.put("ok", true);
            root.put("max_frames_per_camera", frameArchive.maxFramesPerCamera());
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
            return;
        }
        if ("DELETE".equalsIgnoreCase(method)) {
            int deleted = frameArchive.clearAll();
            ObjectNode root = JSON.createObjectNode();
            root.put("ok", true);
            root.put("deleted", deleted);
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
            return;
        }
        HttpResponses.methodNotAllowed(ctx);
    }

    void handleArchiveRoot(HttpRequestContext ctx) throws IOException {
        if (frameArchive == null || !frameArchive.enabled()) {
            HttpResponses.sendJsonError(ctx, 503, "frame archive disabled");
            return;
        }
        if ("DELETE".equalsIgnoreCase(ctx.method())) {
            HttpResponses.corsJson(ctx.exchange());
            byte[] raw = ctx.readBody();
            Map<String, Object> body = raw.length == 0 ? Map.of() : JSON.readValue(raw, new TypeReference<>() {});
            List<Integer> cameraIds = HttpJsonCameraIds.parse(body);
            int deleted = 0;
            if (cameraIds.isEmpty()) {
                deleted = frameArchive.clearAll();
            } else {
                for (Integer cameraId : cameraIds) {
                    deleted += frameArchive.clearCamera(cameraId);
                }
            }
            ObjectNode root = JSON.createObjectNode();
            root.put("ok", true);
            root.put("deleted", deleted);
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
            return;
        }
        HttpResponses.methodNotAllowed(ctx);
    }

    void handleCamera(HttpRequestContext ctx, int cameraId) throws IOException {
        if (frameArchive == null || !frameArchive.enabled()) {
            HttpResponses.sendJsonError(ctx, 503, "frame archive disabled");
            return;
        }
        if ("DELETE".equalsIgnoreCase(ctx.method())) {
            HttpResponses.corsJson(ctx.exchange());
            int deleted = frameArchive.clearCamera(cameraId);
            ObjectNode root = JSON.createObjectNode();
            root.put("ok", true);
            root.put("camera_id", cameraId);
            root.put("deleted", deleted);
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
            return;
        }
        HttpResponses.methodNotAllowed(ctx);
    }

    void handleFrame(HttpRequestContext ctx, int cameraId, long frameId) throws IOException {
        if (frameArchive == null || !frameArchive.enabled()) {
            HttpResponses.sendJsonError(ctx, 503, "frame archive disabled");
            return;
        }
        if ("DELETE".equalsIgnoreCase(ctx.method())) {
            HttpResponses.corsJson(ctx.exchange());
            boolean deleted = frameArchive.deleteFrame(cameraId, frameId);
            if (!deleted) {
                HttpResponses.notFound(ctx);
                return;
            }
            ObjectNode root = JSON.createObjectNode();
            root.put("ok", true);
            root.put("camera_id", cameraId);
            root.put("frame_id", Long.toString(frameId));
            root.put("deleted", 1);
            HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
            return;
        }
        HttpResponses.methodNotAllowed(ctx);
    }

    void handleHistory(HttpRequestContext ctx, int cameraId) throws IOException {
        history.handleHistory(ctx, cameraId);
    }

    void handleArtifact(HttpRequestContext ctx, int cameraId, long frameId, String artifactName) throws IOException {
        history.handleArtifact(ctx, cameraId, frameId, artifactName);
    }
}
