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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FrameArchiveHttpController implements HttpController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ARTIFACT_PATH = Pattern.compile(
            "^/api/frame-archive/cameras/(\\d+)/frames/(\\d+)/(frame\\.jpg|heatmap\\.u8|result\\.json)$"
    );
    private static final Pattern FRAME_PATH = Pattern.compile("^/api/frame-archive/cameras/(\\d+)/frames/(\\d+)$");
    private static final Pattern HISTORY_PATH = Pattern.compile("^/api/frame-archive/cameras/(\\d+)/history$");
    private static final Pattern CAMERA_PATH = Pattern.compile("^/api/frame-archive/cameras/(\\d+)$");

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
        if (path.equals("/api/frame-archive") || path.equals("/api/frame-archive/")) {
            handleArchiveRoot(ctx);
            return;
        }
        Matcher historyMatcher = HISTORY_PATH.matcher(path);
        if (historyMatcher.matches()) {
            handleHistory(ctx, Integer.parseInt(historyMatcher.group(1)));
            return;
        }
        Matcher cameraMatcher = CAMERA_PATH.matcher(path);
        if (cameraMatcher.matches()) {
            handleCamera(ctx, Integer.parseInt(cameraMatcher.group(1)));
            return;
        }
        Matcher frameMatcher = FRAME_PATH.matcher(path);
        if (frameMatcher.matches()) {
            handleFrame(ctx, Integer.parseInt(frameMatcher.group(1)), Long.parseLong(frameMatcher.group(2)));
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

    private void handleArchiveRoot(HttpRequestContext ctx) throws IOException {
        if (frameArchive == null || !frameArchive.enabled()) {
            HttpResponses.sendJsonError(ctx, 503, "frame archive disabled");
            return;
        }
        if ("DELETE".equalsIgnoreCase(ctx.method())) {
            HttpResponses.corsJson(ctx.exchange());
            byte[] raw = ctx.readBody();
            Map<String, Object> body = raw.length == 0 ? Map.of() : JSON.readValue(raw, new TypeReference<>() {});
            List<Integer> cameraIds = parseCameraIds(body);
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

    private void handleCamera(HttpRequestContext ctx, int cameraId) throws IOException {
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

    private void handleFrame(HttpRequestContext ctx, int cameraId, long frameId) throws IOException {
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
                item.put("heatmap_width", frame.heatmapWidth());
                item.put("heatmap_height", frame.heatmapHeight());
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

    private static List<Integer> parseCameraIds(Map<String, Object> body) {
        Set<Integer> out = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        Object single = body.get("cameraId");
        Integer singleId = parseCameraId(single);
        if (singleId != null) {
            out.add(singleId);
        }
        Object many = body.get("cameraIds");
        if (many instanceof Iterable<?> iterable) {
            for (Object rawId : iterable) {
                Integer cameraId = parseCameraId(rawId);
                if (cameraId != null) {
                    out.add(cameraId);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static Integer parseCameraId(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
