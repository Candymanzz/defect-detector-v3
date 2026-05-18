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
import java.util.List;
import java.util.Map;

public final class CameraPreviewHttpController implements HttpController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CameraPreviewStore store;

    public CameraPreviewHttpController(CameraPreviewStore store) {
        this.store = store;
    }

    public void listCameras(HttpRequestContext ctx) throws IOException {
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        HttpResponses.corsJson(ctx.exchange());
        ArrayNode ids = JSON.createArrayNode();
        List<Integer> keys = new ArrayList<>(store.latestByCamera().keySet());
        Collections.sort(keys);
        for (int cam : keys) {
            CameraPreviewStore.Latest l = store.latest(cam).orElse(null);
            if (l == null) {
                continue;
            }
            boolean hasCur = l.currentJpeg() != null && l.currentJpegWidth() > 0 && Files.isRegularFile(l.currentJpeg());
            boolean hasHm = l.heatmapU8() != null && l.heatmapU8Width() > 0 && l.heatmapU8Height() > 0
                    && Files.isRegularFile(l.heatmapU8());
            if (hasCur || hasHm) {
                ids.add(cam);
            }
        }
        ObjectNode root = JSON.createObjectNode();
        root.set("cameras", ids);
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
        } catch (Exception ignored) {
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

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        String path = ctx.path();
        if (path.startsWith("/api/heatmap-artifact/")) {
            handleHeatmapArtifact(ctx);
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
}
