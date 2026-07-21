package com.example.iml.orchestrator.integration.clientws.outbound;

import com.example.iml.orchestrator.integration.clientws.bundle.FpZoneNorm;
import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsInvalidCaptureDescriptorException;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsJsonSerializationException;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsSendFailedException;
import com.example.iml.orchestrator.integration.clientws.protocol.WsMessageTypes;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsReferenceContext;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.clientws.util.WsTextUtil;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.preview.PreviewWsFrame;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Сборка и отправка исходящих WebSocket-сообщений (server.*).
 */
public final class WsOutboundMessenger {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Logger log;
    private final ClientWsConfig cfg;
    private final ClientWsReferenceContext referenceContext;
    private final Supplier<ClientWsSessionState> sessionState;

    public WsOutboundMessenger(
            Logger log,
            ClientWsConfig cfg,
            ClientWsReferenceContext referenceContext,
            Supplier<ClientWsSessionState> sessionState
    ) {
        this.log = log;
        this.cfg = cfg;
        this.referenceContext = referenceContext;
        this.sessionState = sessionState;
    }

    public void sendHello(WebSocket conn) {
        try {
            sendRaw(conn, buildHelloJson(), WsMessageTypes.SERVER_HELLO);
            log.info("client_ws sent type={} session_state={}", WsMessageTypes.SERVER_HELLO, sessionState.get());
        } catch (ClientWsJsonSerializationException e) {
            log.warn("client_ws hello failed: {}", e.getMessage());
        } catch (ClientWsSendFailedException e) {
            log.warn("client_ws hello send failed: {}", e.getMessage());
        }
    }

    public void sendPreviewFrame(
            WebSocket conn,
            int cameraId,
            String productType,
            String detectorId,
            Map<String, Object> captureHeader,
            String httpPath
    ) {
        try {
            long frameIdLong = YamlScalars.toLong(captureHeader.get("frame_id"), -1L);
            String shmName = String.valueOf(captureHeader.get("shm_name")).trim();
            String json = buildPreviewFrameJson(
                    cameraId,
                    productType,
                    detectorId,
                    captureHeader,
                    frameIdLong,
                    shmName,
                    httpPath
            );
            sendRaw(conn, json, WsMessageTypes.SERVER_PREVIEW_FRAME);
            log.debug("client_ws sent type={} camera_id={} frame_id={}", WsMessageTypes.SERVER_PREVIEW_FRAME, cameraId, frameIdLong);
        } catch (ClientWsInvalidCaptureDescriptorException | ClientWsJsonSerializationException e) {
            log.debug("client_ws preview_frame build failed: {}", e.getMessage());
        } catch (ClientWsSendFailedException e) {
            log.debug("client_ws preview_frame send failed: {}", e.getMessage());
        }
    }

    public void sendPreviewBatch(
            WebSocket conn,
            long lineSeq,
            long serverTsMs,
            List<PreviewWsFrame> frames
    ) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", WsMessageTypes.SERVER_PREVIEW_BATCH);
            root.put("protocol_version", cfg.protocolVersion());
            root.put("message_id", UUID.randomUUID().toString());
            ObjectNode payload = JSON.createObjectNode();
            payload.put("line_seq", lineSeq);
            payload.put("server_ts_ms", serverTsMs);
            ArrayNode arr = JSON.createArrayNode();
            for (PreviewWsFrame frame : frames) {
                long frameIdLong = YamlScalars.toLong(frame.captureHeader().get("frame_id"), -1L);
                String shmName = String.valueOf(frame.captureHeader().get("shm_name")).trim();
                ObjectNode framePayload = buildPreviewFramePayloadNode(
                        frame.cameraId(),
                        frame.productType(),
                        frame.detectorId(),
                        frame.captureHeader(),
                        frameIdLong,
                        shmName,
                        frame.httpPath(),
                        serverTsMs
                );
                arr.add(framePayload);
            }
            payload.set("frames", arr);
            root.set("payload", payload);
            sendRaw(conn, writeJson(root), WsMessageTypes.SERVER_PREVIEW_BATCH);
            log.info("client_ws sent type={} line_seq={} cameras={}", WsMessageTypes.SERVER_PREVIEW_BATCH, lineSeq, frames.size());
        } catch (ClientWsInvalidCaptureDescriptorException | ClientWsJsonSerializationException e) {
            log.debug("client_ws preview_batch build failed: {}", e.getMessage());
        } catch (ClientWsSendFailedException e) {
            log.debug("client_ws preview_batch send failed: {}", e.getMessage());
        }
    }

    public void sendInspectResult(
            WebSocket conn,
            int cameraId,
            String productType,
            String detectorId,
            InspectionDecision decision,
            Map<String, Object> captureHeader,
            long frameId,
            long inspectionId,
            String shmName,
            Path heatmapU8Path,
            int heatmapW,
            int heatmapH,
            String currentHttpPath,
            String heatmapArtifactTokenOrNull,
            boolean includeHeatmapFilePathInWs,
            String inspectionArtifactBundleId
    ) {
        try {
            if (decision == null) {
                log.warn("client_ws inspect_result missing decision: camera_id={} frame_id={}", cameraId, frameId);
            }
            String json = buildInspectResultJson(
                    cameraId,
                    productType,
                    detectorId,
                    decision,
                    captureHeader,
                    frameId,
                    inspectionId,
                    shmName,
                    heatmapU8Path,
                    heatmapW,
                    heatmapH,
                    currentHttpPath,
                    heatmapArtifactTokenOrNull,
                    includeHeatmapFilePathInWs,
                    inspectionArtifactBundleId
            );
            sendRaw(conn, json, WsMessageTypes.SERVER_INSPECT_RESULT);
            log.info(
                    "client_ws sent type={} camera_id={} frame_id={} inspection_id={}",
                    WsMessageTypes.SERVER_INSPECT_RESULT,
                    cameraId,
                    frameId,
                    inspectionId
            );
        } catch (ClientWsJsonSerializationException | ClientWsInvalidCaptureDescriptorException e) {
            log.debug("client_ws inspect_result build failed: {}", e.getMessage());
        } catch (ClientWsSendFailedException e) {
            log.debug("client_ws inspect_result send failed: {}", e.getMessage());
        }
    }

    public void sendInspectBucketResult(WebSocket conn, BucketFanOutResult result) {
        try {
            sendRaw(conn, buildInspectBucketResultJson(result), WsMessageTypes.SERVER_INSPECT_BUCKET_RESULT);
            log.info(
                    "client_ws sent type={} group_id={} trigger_sequence={} pass={}",
                    WsMessageTypes.SERVER_INSPECT_BUCKET_RESULT,
                    result.groupId(),
                    result.triggerSequence(),
                    result.overallPass()
            );
        } catch (ClientWsJsonSerializationException e) {
            log.debug("client_ws inspect_bucket_result build failed: {}", e.getMessage());
        } catch (ClientWsSendFailedException e) {
            log.debug("client_ws inspect_bucket_result send failed: {}", e.getMessage());
        }
    }

    private String buildInspectBucketResultJson(BucketFanOutResult result) throws ClientWsJsonSerializationException {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_INSPECT_BUCKET_RESULT);
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = JSON.createObjectNode();
        payload.put("group_id", result.groupId());
        payload.put("trigger_sequence", result.triggerSequence());
        payload.put("overall_pass", result.overallPass());
        ArrayNode cameraIds = JSON.createArrayNode();
        for (Integer cameraId : result.bucketCameraIds()) {
            cameraIds.add(cameraId);
        }
        payload.set("bucket_camera_ids", cameraIds);
        ArrayNode frames = JSON.createArrayNode();
        for (Map.Entry<Integer, InspectionDecision> entry : result.frameDecisions().entrySet()) {
            InspectionDecision decision = entry.getValue();
            if (decision == null) {
                continue;
            }
            ObjectNode frame = JSON.createObjectNode();
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
        ArrayNode rejectCameraIds = JSON.createArrayNode();
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
        return writeJson(root);
    }

    public void sendStreamStarted(WebSocket conn, int cameraId, int maxFps, String httpPath, String mjpegPath) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", WsMessageTypes.SERVER_STREAM_STARTED);
            root.put("protocol_version", cfg.protocolVersion());
            root.put("message_id", UUID.randomUUID().toString());
            ObjectNode payload = JSON.createObjectNode();
            payload.put("ok", true);
            payload.put("camera_id", cameraId);
            payload.put("max_fps", maxFps);
            if (httpPath != null && !httpPath.isBlank()) {
                payload.put("http_path", httpPath);
            }
            if (mjpegPath != null && !mjpegPath.isBlank()) {
                payload.put("mjpeg_path", mjpegPath);
            }
            root.set("payload", payload);
            sendRaw(conn, writeJson(root), WsMessageTypes.SERVER_STREAM_STARTED);
            log.info("client_ws sent type={} camera_id={} max_fps={}", WsMessageTypes.SERVER_STREAM_STARTED, cameraId, maxFps);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws stream_started send failed: {}", e.getMessage());
        }
    }

    public void sendStreamStopped(WebSocket conn, int cameraId) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", WsMessageTypes.SERVER_STREAM_STOPPED);
            root.put("protocol_version", cfg.protocolVersion());
            root.put("message_id", UUID.randomUUID().toString());
            ObjectNode payload = JSON.createObjectNode();
            payload.put("ok", true);
            payload.put("camera_id", cameraId);
            root.set("payload", payload);
            sendRaw(conn, writeJson(root), WsMessageTypes.SERVER_STREAM_STOPPED);
            log.info("client_ws sent type={} camera_id={}", WsMessageTypes.SERVER_STREAM_STOPPED, cameraId);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws stream_stopped send failed: {}", e.getMessage());
        }
    }

    public void sendLightBrightnessAck(
            WebSocket conn,
            JsonNode requestRoot,
            java.util.Map<String, Integer> brightnessByEndpoint,
            int defaultBrightnessPercent
    ) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", WsMessageTypes.SERVER_LIGHT_BRIGHTNESS_ACK);
            root.put("protocol_version", cfg.protocolVersion());
            copyRequestMessageId(root, requestRoot);
            ObjectNode payload = JSON.createObjectNode();
            payload.put("ok", true);
            payload.put("default_brightness_percent", defaultBrightnessPercent);
            payload.put("brightness_percent", defaultBrightnessPercent);
            com.fasterxml.jackson.databind.node.ObjectNode endpoints = JSON.createObjectNode();
            for (var e : brightnessByEndpoint.entrySet()) {
                endpoints.put(e.getKey(), e.getValue());
            }
            payload.set("endpoints", endpoints);
            root.set("payload", payload);
            sendRaw(conn, writeJson(root), WsMessageTypes.SERVER_LIGHT_BRIGHTNESS_ACK);
            log.info("client_ws sent type={} brightness={}", WsMessageTypes.SERVER_LIGHT_BRIGHTNESS_ACK, brightnessByEndpoint);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws light_brightness_ack send failed: {}", e.getMessage());
        }
    }

    public void sendReferenceBundleAck(WebSocket conn, JsonNode requestRoot) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", WsMessageTypes.SERVER_REFERENCE_BUNDLE_ACK);
            root.put("protocol_version", cfg.protocolVersion());
            copyRequestMessageId(root, requestRoot);
            ObjectNode payload = JSON.createObjectNode();
            payload.put("ok", true);
            root.set("payload", payload);
            sendRaw(conn, writeJson(root), WsMessageTypes.SERVER_REFERENCE_BUNDLE_ACK);
            log.info("client_ws sent type={}", WsMessageTypes.SERVER_REFERENCE_BUNDLE_ACK);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws ack send failed: {}", e.getMessage());
        }
    }

    public void sendFpZonesAck(WebSocket conn, JsonNode requestRoot, boolean ok) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", WsMessageTypes.SERVER_FP_ZONES_ACK);
            root.put("protocol_version", cfg.protocolVersion());
            copyRequestMessageId(root, requestRoot);
            ObjectNode payload = JSON.createObjectNode();
            payload.put("ok", ok);
            payload.put("server_ts_ms", System.currentTimeMillis());
            root.set("payload", payload);
            sendRaw(conn, writeJson(root), WsMessageTypes.SERVER_FP_ZONES_ACK);
            log.info("client_ws sent type={} ok={}", WsMessageTypes.SERVER_FP_ZONES_ACK, ok);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws fp_zones_ack send failed: {}", e.getMessage());
        }
    }

    public void sendActiveReferenceViewAck(WebSocket conn, JsonNode requestRoot, int viewIndex) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", WsMessageTypes.SERVER_ACTIVE_REFERENCE_VIEW_ACK);
            root.put("protocol_version", cfg.protocolVersion());
            copyRequestMessageId(root, requestRoot);
            ObjectNode payload = JSON.createObjectNode();
            payload.put("ok", true);
            payload.put("view_index", viewIndex);
            payload.put("server_ts_ms", System.currentTimeMillis());
            root.set("payload", payload);
            sendRaw(conn, writeJson(root), WsMessageTypes.SERVER_ACTIVE_REFERENCE_VIEW_ACK);
            log.info("client_ws sent type={} view_index={}", WsMessageTypes.SERVER_ACTIVE_REFERENCE_VIEW_ACK, viewIndex);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws active_reference_view_ack send failed: {}", e.getMessage());
        }
    }

    public void sendSessionState(WebSocket conn, ClientWsSessionState state) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", WsMessageTypes.SERVER_STATE);
            root.put("protocol_version", cfg.protocolVersion());
            root.put("message_id", UUID.randomUUID().toString());
            ObjectNode payload = JSON.createObjectNode();
            payload.put("session_state", state.name());
            payload.put("server_ts_ms", System.currentTimeMillis());
            root.set("payload", payload);
            sendRaw(conn, writeJson(root), WsMessageTypes.SERVER_STATE);
            log.info("client_ws sent type={} session_state={}", WsMessageTypes.SERVER_STATE, state);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws state send failed: {}", e.getMessage());
        }
    }

    public void sendPlcFinsTraffic(WebSocket conn, com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent event) {
        if (event == null) {
            return;
        }
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", WsMessageTypes.SERVER_PLC_FINS_TRAFFIC);
            root.put("protocol_version", cfg.protocolVersion());
            root.put("message_id", UUID.randomUUID().toString());
            ObjectNode payload = JSON.createObjectNode();
            payload.put("direction", event.direction());
            payload.put("operation", event.operation());
            if (event.signal() != null && !event.signal().isBlank()) {
                payload.put("signal", event.signal());
            }
            payload.put("area", event.area());
            payload.put("address", event.address());
            if (event.value() != null) {
                payload.set("value", JSON.valueToTree(event.value()));
            }
            payload.put("hex_frame", event.hexFrame() == null ? "" : event.hexFrame());
            if (event.sid() != null) {
                payload.put("sid", event.sid());
            }
            if (event.endCode() != null) {
                payload.put("end_code", event.endCode());
            }
            payload.put("ok", event.ok());
            if (event.error() != null && !event.error().isBlank()) {
                payload.put("error", event.error());
            }
            payload.put("server_ts_ms", event.serverTsMs());
            root.set("payload", payload);
            sendRaw(conn, writeJson(root), WsMessageTypes.SERVER_PLC_FINS_TRAFFIC);
            log.debug(
                    "client_ws sent type={} direction={} op={} address={}",
                    WsMessageTypes.SERVER_PLC_FINS_TRAFFIC,
                    event.direction(),
                    event.operation(),
                    event.address()
            );
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.debug("client_ws plc_fins_traffic send failed: {}", e.getMessage());
        }
    }

    public void sendError(WebSocket conn, String code, String message) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", WsMessageTypes.SERVER_ERROR);
            root.put("protocol_version", cfg.protocolVersion());
            root.put("message_id", UUID.randomUUID().toString());
            ObjectNode payload = JSON.createObjectNode();
            payload.put("code", code == null ? "error" : code);
            payload.put("message", message == null ? "" : WsTextUtil.truncate(message, 800));
            root.set("payload", payload);
            sendRaw(conn, writeJson(root), WsMessageTypes.SERVER_ERROR);
            log.info("client_ws sent type={} code={}", WsMessageTypes.SERVER_ERROR, code);
        } catch (ClientWsJsonSerializationException | ClientWsSendFailedException e) {
            log.warn("client_ws error send failed: {}", e.getMessage());
        }
    }

    private String buildHelloJson() throws ClientWsJsonSerializationException {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_HELLO);
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = JSON.createObjectNode();
        payload.put("session_state", sessionState.get().name());
        payload.put("server_ts_ms", System.currentTimeMillis());
        root.set("payload", payload);
        return writeJson(root);
    }

    private String buildPreviewFrameJson(
            int cameraId,
            String productType,
            String detectorId,
            Map<String, Object> captureHeader,
            long frameIdLong,
            String shmName,
            String httpPath
    ) throws ClientWsJsonSerializationException, ClientWsInvalidCaptureDescriptorException {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_PREVIEW_FRAME);
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        root.set("payload", buildPreviewFramePayloadNode(
                cameraId,
                productType,
                detectorId,
                captureHeader,
                frameIdLong,
                shmName,
                httpPath,
                System.currentTimeMillis()
        ));
        return writeJson(root);
    }

    private ObjectNode buildPreviewFramePayloadNode(
            int cameraId,
            String productType,
            String detectorId,
            Map<String, Object> captureHeader,
            long frameIdLong,
            String shmName,
            String httpPath,
            long serverTsMs
    ) throws ClientWsInvalidCaptureDescriptorException {
        ObjectNode current = buildCurrentShmObjectNode(cameraId, captureHeader, frameIdLong, shmName);
        if (httpPath != null && !httpPath.isBlank()) {
            current.put("http_path", httpPath);
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("camera_id", cameraId);
        payload.put("frame_id", Long.toString(frameIdLong));
        payload.put("session_state", sessionState.get().name());
        payload.set("current", current);
        if (httpPath != null && !httpPath.isBlank()) {
            payload.put("http_path", httpPath);
        }
        ObjectNode det = JSON.createObjectNode();
        if (detectorId != null && !detectorId.isBlank()) {
            det.put("detector_id", detectorId);
        }
        if (productType != null && !productType.isBlank()) {
            det.put("product_type", productType);
        }
        payload.set("detector", det);
        payload.put("server_ts_ms", serverTsMs);
        return payload;
    }

    private String buildInspectResultJson(
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
        ObjectNode current = buildCurrentShmObjectNode(cameraId, captureHeader, frameIdLong, shmName);
        String previewHttpPath = currentHttpPath == null ? "" : currentHttpPath.trim();
        if (!previewHttpPath.isEmpty()) {
            current.put("http_path", previewHttpPath);
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_INSPECT_RESULT);
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = JSON.createObjectNode();
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
            ObjectNode hm = JSON.createObjectNode();
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
        ObjectNode det = JSON.createObjectNode();
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
        payload.set("fp_zones", fpZonesJsonArray(cameraId));
        int hmw = referenceContext.effectiveHeatmapWidth();
        int hmh = referenceContext.effectiveHeatmapHeight();
        if (hmw > 0 && hmh > 0) {
            ObjectNode coo = payload.putObject("fp_coordinate_space");
            coo.put("heatmap_width", hmw);
            coo.put("heatmap_height", hmh);
        }
        payload.put("server_ts_ms", System.currentTimeMillis());
        root.set("payload", payload);
        return writeJson(root);
    }

    private ObjectNode buildCurrentShmObjectNode(
            int cameraId,
            Map<String, Object> header,
            long frameIdLong,
            String shmName
    ) throws ClientWsInvalidCaptureDescriptorException {
        int width = YamlScalars.toInt(header.get("width"), 0);
        int height = YamlScalars.toInt(header.get("height"), 0);
        if (width <= 0 || height <= 0) {
            throw new ClientWsInvalidCaptureDescriptorException("width/height");
        }
        String pixelFormat = "bgr_u8";
        Object pf = header.get("pixel_format");
        if (pf != null) {
            String pfs = String.valueOf(pf).trim();
            if (!pfs.isEmpty()) {
                pixelFormat = pfs;
            }
        }
        int channels = YamlScalars.toInt(header.get("channels"), 0);
        if (channels <= 0) {
            channels = "gray_u8".equalsIgnoreCase(pixelFormat) ? 1 : 3;
        }
        int stride = YamlScalars.toInt(header.get("stride"), 0);
        if (stride <= 0) {
            stride = width * channels;
        }
        if (stride < width * channels) {
            throw new ClientWsInvalidCaptureDescriptorException("stride");
        }
        long shmOffsetLong = YamlScalars.toLong(header.get("shm_offset"), 0L);
        if (shmOffsetLong < 0 || shmOffsetLong > Integer.MAX_VALUE) {
            throw new ClientWsInvalidCaptureDescriptorException("shm_offset");
        }
        int shmOffset = (int) shmOffsetLong;
        ObjectNode current = JSON.createObjectNode();
        current.put("camera_id", cameraId);
        current.put("frame_id", Long.toString(frameIdLong));
        current.put("shm_name", shmName);
        current.put("width", width);
        current.put("height", height);
        current.put("stride", stride);
        current.put("shm_offset", shmOffset);
        current.put("pixel_format", pixelFormat);
        current.put("channels", channels);
        Object exp = header.get("expires_at_ms");
        if (exp instanceof Number n) {
            current.put("expires_at_ms", n.longValue());
        }
        Object ttl = header.get("ttl_ms");
        if (ttl instanceof Number n) {
            current.put("ttl_ms", n.intValue());
        }
        Object rt = header.get("read_token");
        if (rt != null) {
            String tok = String.valueOf(rt).trim();
            if (!tok.isEmpty()) {
                current.put("read_token", tok);
            }
        }
        return current;
    }

    private ArrayNode fpZonesJsonArray(int cameraId) {
        ArrayNode arr = JSON.createArrayNode();
        for (FpZoneNorm z : referenceContext.effectiveFpZones()) {
            if (z.cameraId() != null && z.cameraId() != cameraId) {
                continue;
            }
            ObjectNode zo = arr.addObject();
            if (z.id() != null && !z.id().isBlank()) {
                zo.put("id", z.id());
            }
            if (z.cameraId() != null) {
                zo.put("camera_id", z.cameraId());
            }
            zo.put("note", z.note() != null ? z.note() : "");
            ArrayNode pts = zo.putArray("points_norm_heatmap");
            for (FpZoneNorm.PointNorm p : z.pointsNormHeatmap()) {
                ObjectNode po = pts.addObject();
                po.put("x", p.x());
                po.put("y", p.y());
            }
        }
        return arr;
    }

    private static void copyRequestMessageId(ObjectNode root, JsonNode requestEnvelope) {
        if (requestEnvelope != null && requestEnvelope.hasNonNull("message_id")) {
            String mid = requestEnvelope.get("message_id").asText("").trim();
            if (!mid.isEmpty()) {
                root.put("message_id", mid);
                return;
            }
        }
        root.put("message_id", UUID.randomUUID().toString());
    }

    private String writeJson(ObjectNode root) throws ClientWsJsonSerializationException {
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new ClientWsJsonSerializationException("json write failed", e);
        }
    }

    private static void sendRaw(WebSocket conn, String json, String type) throws ClientWsSendFailedException {
        try {
            conn.send(json);
        } catch (RuntimeException e) {
            throw new ClientWsSendFailedException(type, e);
        }
    }

}
