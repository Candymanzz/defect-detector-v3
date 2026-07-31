package com.example.iml.orchestrator.integration.clientws.outbound.messages;

import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsInvalidCaptureDescriptorException;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsJsonSerializationException;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundJson;
import com.example.iml.orchestrator.integration.clientws.outbound.payload.CurrentShmObjectBuilder;
import com.example.iml.orchestrator.integration.clientws.protocol.WsMessageTypes;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.preview.PreviewWsFrame;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Preview frame / batch outbound JSON builders.
 */
public final class PreviewOutboundJson {

    private final ClientWsConfig cfg;
    private final Supplier<ClientWsSessionState> sessionState;

    public PreviewOutboundJson(ClientWsConfig cfg, Supplier<ClientWsSessionState> sessionState) {
        this.cfg = cfg;
        this.sessionState = sessionState;
    }

    public String buildPreviewFrameJson(
            int cameraId,
            String productType,
            String detectorId,
            Map<String, Object> captureHeader,
            long frameIdLong,
            String shmName,
            String httpPath
    ) throws ClientWsJsonSerializationException, ClientWsInvalidCaptureDescriptorException {
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
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
        return WsOutboundJson.writeJson(root);
    }

    public String buildPreviewBatchJson(
            long lineSeq,
            long serverTsMs,
            List<PreviewWsFrame> frames
    ) throws ClientWsJsonSerializationException, ClientWsInvalidCaptureDescriptorException {
        ObjectNode root = WsOutboundJson.JSON.createObjectNode();
        root.put("type", WsMessageTypes.SERVER_PREVIEW_BATCH);
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("line_seq", lineSeq);
        payload.put("server_ts_ms", serverTsMs);
        ArrayNode arr = WsOutboundJson.JSON.createArrayNode();
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
        return WsOutboundJson.writeJson(root);
    }

    public ObjectNode buildPreviewFramePayloadNode(
            int cameraId,
            String productType,
            String detectorId,
            Map<String, Object> captureHeader,
            long frameIdLong,
            String shmName,
            String httpPath,
            long serverTsMs
    ) throws ClientWsInvalidCaptureDescriptorException {
        ObjectNode current = CurrentShmObjectBuilder.buildCurrentShmObjectNode(cameraId, captureHeader, frameIdLong, shmName);
        if (httpPath != null && !httpPath.isBlank()) {
            current.put("http_path", httpPath);
        }
        ObjectNode payload = WsOutboundJson.JSON.createObjectNode();
        payload.put("camera_id", cameraId);
        payload.put("frame_id", Long.toString(frameIdLong));
        payload.put("session_state", sessionState.get().name());
        payload.set("current", current);
        if (httpPath != null && !httpPath.isBlank()) {
            payload.put("http_path", httpPath);
        }
        ObjectNode det = WsOutboundJson.JSON.createObjectNode();
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
}
