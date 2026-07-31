package com.example.iml.orchestrator.integration.clientws.outbound.messages;

import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsInvalidCaptureDescriptorException;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsJsonSerializationException;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundJson;
import com.example.iml.orchestrator.integration.clientws.outbound.payload.CurrentShmObjectBuilder;
import com.example.iml.orchestrator.integration.clientws.outbound.payload.FpZonesJsonBuilder;
import com.example.iml.orchestrator.integration.clientws.protocol.WsMessageTypes;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsReferenceContext;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Inspect result / bucket result outbound JSON builders.
 */
public final class InspectOutboundJson {

    private final ClientWsConfig cfg;
    private final ClientWsReferenceContext referenceContext;
    private final Supplier<ClientWsSessionState> sessionState;

    public InspectOutboundJson(
            ClientWsConfig cfg,
            ClientWsReferenceContext referenceContext,
            Supplier<ClientWsSessionState> sessionState
    ) {
        this.cfg = cfg;
        this.referenceContext = referenceContext;
        this.sessionState = sessionState;
    }

    public String buildInspectBucketResultJson(BucketFanOutResult result) throws ClientWsJsonSerializationException {
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_INSPECT_BUCKET_RESULT);
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("group_id", result.groupId());
        payload.put("trigger_sequence", result.triggerSequence());
        payload.put("overall_pass", result.overallPass());
        ArrayNode cameraIds = WsOutboundJson.JSON.createArrayNode();
        for (Integer cameraId : result.bucketCameraIds()) {
            cameraIds.add(cameraId);
        }
        payload.set("bucket_camera_ids", cameraIds);
        ArrayNode frames = WsOutboundJson.JSON.createArrayNode();
        for (Map.Entry<Integer, InspectionDecision> entry : result.frameDecisions().entrySet()) {
            InspectionDecision decision = entry.getValue();
            if (decision == null) {
                continue;
            }
            ObjectNode frame = WsOutboundJson.JSON.createObjectNode();
            frame.put("camera_id", decision.cameraId());
            frame.put("frame_id", Long.toString(decision.frameId()));
            frame.put("overall_pass", decision.overallPass());
            frame.put("action", decision.action());
            frame.put("anomaly_score", decision.anomalyScore());
            frame.put("python_status", decision.pythonStatus());
            frame.put("geometry_status", decision.geometryStatus());
            frames.add(frame);
        }
        payload.set("frames", frames);
        ArrayNode rejectCameraIds = WsOutboundJson.JSON.createArrayNode();
        for (Integer cameraId : result.bucketCameraIds()) {
            InspectionDecision decision = result.frameDecisions().get(cameraId);
            if (decision != null && !decision.overallPass()) {
                rejectCameraIds.add(cameraId);
            }
        }
        payload.set("reject_camera_ids", rejectCameraIds);
        payload.put("session_state", sessionState.get().name());
        payload.put("server_ts_ms", System.currentTimeMillis());
        root.set("payload", payload);
        return WsOutboundJson.writeJson(root);
    }

    public String buildInspectResultJson(
            int cameraId,
            String productType,
            String detectorId,
            InspectionDecision decision,
            Map<String, Object> captureHeader,
            long frameIdLong,
            long inspectionId,
            String shmName,
            Path heatmapU8Path,
            int heatmapW,
            int heatmapH,
            String currentHttpPath,
            String heatmapArtifactTokenOrNull,
            boolean includeHeatmapFilePathInWs,
            String inspectionArtifactBundleId
    ) throws ClientWsJsonSerializationException, ClientWsInvalidCaptureDescriptorException {
        ObjectNode current = CurrentShmObjectBuilder.buildCurrentShmObjectNode(cameraId, captureHeader, frameIdLong, shmName);
        String previewHttpPath = currentHttpPath == null ? "" : currentHttpPath.trim();
        if (!previewHttpPath.isEmpty()) {
            current.put("http_path", previewHttpPath);
        }
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_INSPECT_RESULT);
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("camera_id", cameraId);
        payload.put("frame_id", Long.toString(frameIdLong));
        payload.put("inspection_id", Long.toString(inspectionId));
        payload.put("session_state", sessionState.get().name());
        payload.set("current", current);
        String bundleId = inspectionArtifactBundleId == null ? "" : inspectionArtifactBundleId.trim();
        if (!bundleId.isEmpty()) {
            payload.put("artifact_bundle_id", bundleId);
        }
        if (!previewHttpPath.isEmpty()) {
            payload.put("http_path", previewHttpPath);
        }
        boolean hasHm = heatmapU8Path != null
                && heatmapW > 0
                && heatmapH > 0
                && Files.isRegularFile(heatmapU8Path);
        if (hasHm) {
            ObjectNode hm = WsOutboundJson.JSON.createObjectNode();
            hm.put("width", heatmapW);
            hm.put("height", heatmapH);
            hm.put("pixel_format", "gray_u8");
            hm.put("channels", 1);
            String tok = heatmapArtifactTokenOrNull == null ? "" : heatmapArtifactTokenOrNull.trim();
            if (previewHttpPath.contains("/api/frame-archive/") && previewHttpPath.endsWith("/frame.jpg")) {
                hm.put("http_path", previewHttpPath.substring(0, previewHttpPath.length() - "frame.jpg".length())
                        + "heatmap.u8");
            } else if (!bundleId.isEmpty()) {
                hm.put("http_path", "/api/inspection-artifacts/" + bundleId + "/heatmap.u8");
            } else if (!tok.isEmpty()) {
                hm.put("artifact_id", tok);
                hm.put("http_path", "/api/heatmap-artifact/" + tok);
            }
            if (includeHeatmapFilePathInWs) {
                hm.put("file_path", heatmapU8Path.toAbsolutePath().toString());
            }
            payload.set("heatmap", hm);
        } else {
            payload.putNull("heatmap");
        }
        payload.put("active_reference_view_index", referenceContext.activeReferenceViewIndex());
        ObjectNode det = WsOutboundJson.JSON.createObjectNode();
        if (detectorId != null && !detectorId.isBlank()) {
            det.put("detector_id", detectorId);
        }
        if (productType != null && !productType.isBlank()) {
            det.put("product_type", productType);
        }
        payload.set("detector", det);
        if (decision != null) {
            payload.put("overall_pass", decision.overallPass());
            payload.put("action", decision.action());
            payload.put("anomaly_score", decision.anomalyScore());
            payload.put("python_status", decision.pythonStatus());
            payload.put("geometry_status", decision.geometryStatus());
        } else {
            // Missing aggregation is not a reject decision.
            payload.put("python_status", "UNKNOWN");
            payload.put("geometry_status", "UNKNOWN");
        }
        payload.set("fp_zones", FpZonesJsonBuilder.fpZonesJsonArray(referenceContext, cameraId));
        int hmw = referenceContext.effectiveHeatmapWidth();
        int hmh = referenceContext.effectiveHeatmapHeight();
        if (hmw > 0 && hmh > 0) {
            ObjectNode coo = payload.putObject("fp_coordinate_space");
            coo.put("heatmap_width", hmw);
            coo.put("heatmap_height", hmh);
        }
        payload.put("server_ts_ms", System.currentTimeMillis());
        root.set("payload", payload);
        return WsOutboundJson.writeJson(root);
    }
}
