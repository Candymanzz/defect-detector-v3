package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FrameArchiveHttpController implements HttpController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ARTIFACT_PATH = Pattern.compile(
            "^/api/frame-archive/cameras/(\\d+)/frames/(\\d+)/(frame\\.jpg|heatmap\\.u8|result\\.json)$"
    );
    private static final Pattern HISTORY_PATH = Pattern.compile("^/api/frame-archive/cameras/(\\d+)/history$");

    private final FrameArchiveService frameArchive;

    public FrameArchiveHttpController(FrameArchiveService frameArchive) {
        this.frameArchive = frameArchive;
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        String path = ctx.path();
        if (path.equals("/api/client/frame-archive")) {
            handleSettings(ctx);
            return;
        }
        Matcher historyMatcher = HISTORY_PATH.matcher(path);
        if (historyMatcher.matches()) {
            handleHistory(ctx, Integer.parseInt(historyMatcher.group(1)));
            return;
        }
        Matcher artifactMatcher = ARTIFACT_PATH.matcher(path);
        if (artifactMatcher.matches()) {
            handleArtifact(
                    ctx,
                    Integer.parseInt(artifactMatcher.group(1)),
                    Long.parseLong(artifactMatcher.group(2)),
                    artifactMatcher.group(3)
            );
            return;
        }
        HttpResponses.notFound(ctx);
    }

    private void handleSettings(HttpRequestContext ctx) throws IOException {
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
        HttpResponses.methodNotAllowed(ctx);
    }

    private void handleHistory(HttpRequestContext ctx, int cameraId) throws IOException {
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        if (frameArchive == null || !frameArchive.enabled()) {
            HttpResponses.sendJsonError(ctx, 503, "frame archive disabled");
            return;
        }
        HttpResponses.corsJson(ctx.exchange());
        List<FrameArchiveService.ArchivedFrame> frames = frameArchive.listHistory(cameraId);
        ObjectNode root = JSON.createObjectNode();
        root.put("camera_id", cameraId);
        root.put("max_frames_per_camera", frameArchive.maxFramesPerCamera());
        ArrayNode items = root.putArray("frames");
        for (FrameArchiveService.ArchivedFrame frame : frames) {
            ObjectNode item = items.addObject();
            item.put("frame_id", Long.toString(frame.frameId()));
            item.put("inspection_id", Long.toString(frame.inspectionId()));
            item.put("overall_pass", frame.overallPass());
            item.put("action", frame.action());
            item.put("anomaly_score", frame.anomalyScore());
            item.put("python_status", frame.pythonStatus());
            item.put("geometry_status", frame.geometryStatus());
            item.put("product_type", frame.productType());
            item.put("detector_id", frame.detectorId());
            item.put("saved_at_ms", frame.savedAtEpochMs());
            item.put("has_heatmap", frame.hasHeatmap());
            item.put("frame_url", frameArchive.frameArtifactHttpPath(cameraId, frame.frameId(), "frame.jpg"));
            if (frame.hasHeatmap()) {
                item.put("heatmap_url", frameArchive.frameArtifactHttpPath(cameraId, frame.frameId(), "heatmap.u8"));
            }
            item.put("result_url", frameArchive.frameArtifactHttpPath(cameraId, frame.frameId(), "result.json"));
        }
        HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
    }

    private void handleArtifact(HttpRequestContext ctx, int cameraId, long frameId, String artifactName) throws IOException {
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        if (frameArchive == null || !frameArchive.enabled()) {
            HttpResponses.sendJsonError(ctx, 503, "frame archive disabled");
            return;
        }
        Path artifact = frameArchive.resolveArtifact(cameraId, frameId, artifactName).orElse(null);
        if (artifact == null) {
            HttpResponses.notFound(ctx);
            return;
        }
        String contentType = switch (artifactName) {
            case "frame.jpg" -> "image/jpeg";
            case "heatmap.u8" -> "application/octet-stream";
            case "result.json" -> "application/json; charset=utf-8";
            default -> "application/octet-stream";
        };
        HttpResponses.corsJson(ctx.exchange());
        byte[] body = Files.readAllBytes(artifact);
        HttpResponses.send(ctx, 200, contentType, body);
    }
}
