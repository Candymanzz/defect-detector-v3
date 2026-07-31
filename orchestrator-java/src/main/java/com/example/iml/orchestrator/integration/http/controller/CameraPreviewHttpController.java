package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.ui.CameraPreviewStore;
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
        ArrayNode ids = CameraPreviewJsonBuilder.json().createArrayNode();
        Set<Integer> merged = new LinkedHashSet<>(configuredCameraIds);
        merged.addAll(store.latestByCamera().keySet());
        List<Integer> keys = new ArrayList<>(merged);
        Collections.sort(keys);
        for (int cam : keys) {
            ids.add(cam);
        }
        ObjectNode root = CameraPreviewJsonBuilder.json().createObjectNode();
        root.set("cameras", ids);
        ObjectNode profiles = root.putObject("analysisProfileByCamera");
        for (int cam : keys) {
            profiles.put(String.valueOf(cam), configuredAnalysisProfile(cam));
        }
        HttpResponses.send(ctx, 200, "application/json; charset=utf-8", CameraPreviewJsonBuilder.json().writeValueAsBytes(root));
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
                    HttpResponses.send(
                            ctx,
                            200,
                            "application/json; charset=utf-8",
                            CameraPreviewJsonBuilder.json().writeValueAsBytes(
                                    CameraPreviewJsonBuilder.emptyLatestJson(cam, this::configuredAnalysisProfile)
                            )
                    );
                    return;
                }
                HttpResponses.sendText(ctx, 404, "no data\n");
                return;
            }
            if (uri.endsWith("/latest.json")) {
                HttpResponses.corsJson(ctx.exchange());
                HttpResponses.send(
                        ctx,
                        200,
                        "application/json; charset=utf-8",
                        CameraPreviewJsonBuilder.json().writeValueAsBytes(CameraPreviewJsonBuilder.latestJson(cam, l))
                );
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

    private String configuredAnalysisProfile(int cameraId) {
        return analysisProfileByCamera.getOrDefault(cameraId, "camera-" + cameraId);
    }
}
