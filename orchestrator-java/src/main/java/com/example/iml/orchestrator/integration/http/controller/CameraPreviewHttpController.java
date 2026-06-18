package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.ui.CameraPreviewStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CameraPreviewHttpController implements HttpController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CameraPreviewStore store;
    private final List<Integer> configuredCameraIds;
    private final Map<Integer, String> analysisProfileByCamera;

    public CameraPreviewHttpController(
            CameraPreviewStore store,
            List<Integer> configuredCameraIds,
            Map<Integer, String> analysisProfileByCamera
    ) {
        this.store = store;
        this.configuredCameraIds = configuredCameraIds == null ? List.of() : List.copyOf(configuredCameraIds);
        this.analysisProfileByCamera = analysisProfileByCamera == null ? Map.of() : Map.copyOf(analysisProfileByCamera);
    }

    public void listCameras(HttpRequestContext ctx) throws IOException {
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        HttpResponses.corsJson(ctx.exchange());
        ArrayNode ids = JSON.createArrayNode();
        Set<Integer> merged = new LinkedHashSet<>(configuredCameraIds);
        merged.addAll(store.latestByCamera().keySet());
        List<Integer> keys = new ArrayList<>(merged);
        Collections.sort(keys);
        for (int cam : keys) {
            ids.add(cam);
        }
        ObjectNode root = JSON.createObjectNode();
        root.set("cameras", ids);
        ObjectNode profiles = root.putObject("analysisProfileByCamera");
        for (int cam : keys) {
            profiles.put(String.valueOf(cam), configuredAnalysisProfile(cam));
        }
        HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(root));
    }

    public void handleCameraPath(HttpRequestContext ctx) throws IOException {
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        String uri = ctx.path();
        try {
            String[] parts = uri.split("/");
            if (parts.length < 5) {
                HttpResponses.notFound(ctx);
                return;
            }
            int cam = Integer.parseInt(parts[3]);
            CameraPreviewStore.Latest l = store.latest(cam).orElse(null);
            if (l == null) {
                if (uri.endsWith("/latest.json") && configuredCameraIds.contains(cam)) {
                    HttpResponses.corsJson(ctx.exchange());
                    HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(emptyLatestJson(cam)));
                    return;
                }
                HttpResponses.sendText(ctx, 404, "no data\n");
                return;
            }
            if (uri.endsWith("/latest.json")) {
                HttpResponses.corsJson(ctx.exchange());
                HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(latestJson(cam, l)));
                return;
            }
            if (uri.endsWith("/current.jpg") && l.currentJpeg() != null && Files.isRegularFile(l.currentJpeg())) {
                HttpResponses.send(ctx, 200, "image/jpeg", Files.readAllBytes(l.currentJpeg()));
                return;
            }
            if (uri.endsWith("/heatmap.u8") && l.heatmapU8() != null && Files.isRegularFile(l.heatmapU8())) {
                HttpResponses.send(ctx, 200, "application/octet-stream", Files.readAllBytes(l.heatmapU8()));
                return;
            }
        } catch (NumberFormatException | IOException ignored) {
        }
        HttpResponses.notFound(ctx);
    }

    public void handleHeatmapArtifact(HttpRequestContext ctx) throws IOException {
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        String uri = ctx.path();
        String prefix = "/api/heatmap-artifact/";
        if (!uri.startsWith(prefix)) {
            HttpResponses.notFound(ctx);
            return;
        }
        String token = uri.substring(prefix.length());
        int slash = token.indexOf('/');
        if (slash >= 0) {
            token = token.substring(0, slash);
        }
        if (token.isEmpty()) {
            HttpResponses.notFound(ctx);
            return;
        }
        Path file = store.resolveHeatmapArtifactPath(token);
        if (file == null || !Files.isRegularFile(file)) {
            HttpResponses.notFound(ctx);
            return;
        }
        HttpResponses.send(ctx, 200, "application/octet-stream", Files.readAllBytes(file));
    }

    public void handleInspectionArtifact(HttpRequestContext ctx) throws IOException {
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        String prefix = "/api/inspection-artifacts/";
        String uri = ctx.path();
        if (!uri.startsWith(prefix)) {
            HttpResponses.notFound(ctx);
            return;
        }
        String[] parts = uri.substring(prefix.length()).split("/");
        if (parts.length != 2) {
            HttpResponses.notFound(ctx);
            return;
        }

        String contentType;
        if ("frame.jpg".equals(parts[1]) || "card.jpg".equals(parts[1])) {
            contentType = "image/jpeg";
        } else if ("heatmap.u8".equals(parts[1])) {
            contentType = "application/octet-stream";
        } else {
            HttpResponses.notFound(ctx);
            return;
        }
        byte[] artifact = store.readInspectionArtifact(parts[0], parts[1]);
        if (artifact == null) {
            HttpResponses.notFound(ctx);
            return;
        }
        ctx.exchange().getResponseHeaders().set("Cache-Control", "private, max-age=31536000, immutable");
        HttpResponses.send(ctx, 200, contentType, artifact);
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        String path = ctx.path();
        if (path.startsWith("/api/heatmap-artifact/")) {
            handleHeatmapArtifact(ctx);
        } else if (path.startsWith("/api/inspection-artifacts/")) {
            handleInspectionArtifact(ctx);
        } else if (path.startsWith("/api/camera/")) {
            handleCameraPath(ctx);
        } else if ("/api/cameras".equals(path)) {
            listCameras(ctx);
        } else {
            HttpResponses.notFound(ctx);
        }
    }

    private static ObjectNode latestJson(int cameraId, CameraPreviewStore.Latest l) {
        boolean hasCur = l.currentJpeg() != null && l.currentJpegWidth() > 0 && Files.isRegularFile(l.currentJpeg());
        boolean hasHm = l.heatmapU8() != null && l.heatmapU8Width() > 0 && l.heatmapU8Height() > 0
                && Files.isRegularFile(l.heatmapU8());
        ObjectNode root = JSON.createObjectNode();
        root.put("cameraId", cameraId);
        root.put("frameId", l.frameId());
        root.put("productType", l.productType() == null ? "" : l.productType());
        root.put("detectorId", l.detectorId() == null ? "" : l.detectorId());
        root.put("shmName", l.shmName() == null ? "" : l.shmName());
        root.put("updatedAtMs", l.updatedAtEpochMs());
        if (l.overallPass() != null) {
            root.put("overall_pass", l.overallPass());
        } else {
            root.putNull("overall_pass");
        }
        if (l.action() != null) {
            root.put("action", l.action());
        } else {
            root.putNull("action");
        }
        if (l.anomalyScore() != null) {
            root.put("anomaly_score", l.anomalyScore());
        } else {
            root.putNull("anomaly_score");
        }
        if (l.pythonStatus() != null) {
            root.put("python_status", l.pythonStatus());
        } else {
            root.putNull("python_status");
        }
        if (l.geometryStatus() != null) {
            root.put("geometry_status", l.geometryStatus());
        } else {
            root.putNull("geometry_status");
        }
        root.put("hasCurrent", hasCur);
        root.put("hasHeatmap", hasHm);
        ObjectNode cap = root.putObject("capture");
        cap.put("width", l.captureWidth());
        cap.put("height", l.captureHeight());
        ObjectNode cur = root.putObject("currentJpeg");
        cur.put("width", l.currentJpegWidth());
        cur.put("height", l.currentJpegHeight());
        cur.put("path", "/api/camera/" + cameraId + "/current.jpg");
        ObjectNode hm = root.putObject("heatmapU8");
        hm.put("width", l.heatmapU8Width());
        hm.put("height", l.heatmapU8Height());
        hm.put("path", "/api/camera/" + cameraId + "/heatmap.u8");
        return root;
    }

    private ObjectNode emptyLatestJson(int cameraId) {
        ObjectNode root = JSON.createObjectNode();
        root.put("cameraId", cameraId);
        root.put("frameId", -1);
        root.put("productType", configuredAnalysisProfile(cameraId));
        root.put("detectorId", "");
        root.put("shmName", "");
        root.put("updatedAtMs", 0);
        root.putNull("overall_pass");
        root.putNull("action");
        root.putNull("anomaly_score");
        root.putNull("python_status");
        root.putNull("geometry_status");
        root.put("hasCurrent", false);
        root.put("hasHeatmap", false);
        ObjectNode cap = root.putObject("capture");
        cap.put("width", 0);
        cap.put("height", 0);
        ObjectNode cur = root.putObject("currentJpeg");
        cur.put("width", 0);
        cur.put("height", 0);
        cur.put("path", "/api/camera/" + cameraId + "/current.jpg");
        ObjectNode hm = root.putObject("heatmapU8");
        hm.put("width", 0);
        hm.put("height", 0);
        hm.put("path", "/api/camera/" + cameraId + "/heatmap.u8");
        return root;
    }

    private String configuredAnalysisProfile(int cameraId) {
        return analysisProfileByCamera.getOrDefault(cameraId, "camera-" + cameraId);
    }
}
