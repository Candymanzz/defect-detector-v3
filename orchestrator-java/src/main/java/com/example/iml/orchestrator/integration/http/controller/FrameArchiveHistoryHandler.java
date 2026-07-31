package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** History and artifact GET handlers for frame archive. */
final class FrameArchiveHistoryHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FrameArchiveService frameArchive;

    FrameArchiveHistoryHandler(FrameArchiveService frameArchive) {
        this.frameArchive = frameArchive;
    }

    void handleHistory(HttpRequestContext ctx, int cameraId) throws IOException {
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

    void handleArtifact(HttpRequestContext ctx, int cameraId, long frameId, String artifactName) throws IOException {
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
