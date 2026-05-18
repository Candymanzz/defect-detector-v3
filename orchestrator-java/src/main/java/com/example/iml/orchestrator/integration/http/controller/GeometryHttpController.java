package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GeometryHttpController implements HttpController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GeometrySnapshotCache cache;

    public GeometryHttpController(GeometrySnapshotCache cache) {
        this.cache = cache;
    }

    public void listCameras(HttpRequestContext ctx) throws IOException {
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        HttpResponses.corsJson(ctx.exchange());
        ArrayNode ids = JSON.createArrayNode();
        List<Integer> keys = new ArrayList<>(cache.cameraIds());
        Collections.sort(keys);
        for (int cam : keys) {
            ids.add(cam);
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
            if (parts.length < 6) {
                HttpResponses.notFound(ctx);
                return;
            }
            int cam = Integer.parseInt(parts[4]);
            GeometrySnapshotCache.Snapshot snap = cache.get(cam).orElse(null);
            if (snap == null) {
                HttpResponses.sendText(ctx, 404, "no geometry snapshot\n");
                return;
            }
            if (uri.endsWith("/latest.json")) {
                HttpResponses.corsJson(ctx.exchange());
                HttpResponses.send(ctx, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(geometryLatestJson(cam, snap)));
                return;
            }
        } catch (NumberFormatException ignored) {
        }
        HttpResponses.notFound(ctx);
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        String path = ctx.path();
        if (path.startsWith("/api/geometry/camera/")) {
            handleCameraPath(ctx);
        } else if ("/api/geometry/cameras".equals(path)) {
            listCameras(ctx);
        } else {
            HttpResponses.notFound(ctx);
        }
    }

    private static ObjectNode geometryLatestJson(int cameraId, GeometrySnapshotCache.Snapshot snap) {
        ObjectNode root = JSON.createObjectNode();
        root.put("cameraId", cameraId);
        root.put("frameId", snap.frameId());
        root.put("updatedAtMs", snap.updatedAtEpochMs());
        root.put("latestJsonPath", "/api/geometry/camera/" + cameraId + "/latest.json");
        root.set("geometry", JSON.valueToTree(snap.geometryHeader()));
        return root;
    }
}
