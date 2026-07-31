package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.ui.CameraPreviewStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.util.function.IntFunction;

/** JSON builders for camera preview HTTP responses. */
final class CameraPreviewJsonBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CameraPreviewJsonBuilder() {
    }

    static ObjectMapper json() {
        return JSON;
    }

    static ObjectNode latestJson(int cameraId, CameraPreviewStore.Latest l) {
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

    static ObjectNode emptyLatestJson(int cameraId, IntFunction<String> analysisProfile) {
        ObjectNode root = JSON.createObjectNode();
        root.put("cameraId", cameraId);
        root.put("frameId", -1);
        root.put("productType", analysisProfile.apply(cameraId));
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
}
