package com.example.iml.orchestrator.integration.clientws.outbound;

import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsInvalidCaptureDescriptorException;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsJsonSerializationException;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsSendFailedException;
import com.example.iml.orchestrator.integration.clientws.outbound.messages.ControlOutboundJson;
import com.example.iml.orchestrator.integration.clientws.outbound.messages.InspectOutboundJson;
import com.example.iml.orchestrator.integration.clientws.outbound.messages.PreviewOutboundJson;
import com.example.iml.orchestrator.integration.clientws.protocol.WsMessageTypes;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsReferenceContext;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.preview.PreviewWsFrame;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Сборка и отправка исходящих WebSocket-сообщений (server.*).
 */
public final class WsOutboundMessenger {

    private final Logger log;
    private final PreviewOutboundJson previewJson;
    private final WsInspectOutboundMessenger inspect;
    private final WsControlOutboundMessenger control;

    public WsOutboundMessenger(
            Logger log,
            ClientWsConfig cfg,
            ClientWsReferenceContext referenceContext,
            Supplier<ClientWsSessionState> sessionState
    ) {
        this.log = log;
        this.previewJson = new PreviewOutboundJson(cfg, sessionState);
        this.inspect = new WsInspectOutboundMessenger(log, new InspectOutboundJson(cfg, referenceContext, sessionState));
        this.control = new WsControlOutboundMessenger(log, new ControlOutboundJson(cfg, sessionState), sessionState);
    }

    public void sendHello(WebSocket conn) {
        control.sendHello(conn);
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
            String json = previewJson.buildPreviewFrameJson(
                    cameraId, productType, detectorId, captureHeader, frameIdLong, shmName, httpPath);
            WsOutboundJson.sendRaw(conn, json, WsMessageTypes.SERVER_PREVIEW_FRAME);
            log.debug("client_ws sent type={} camera_id={} frame_id={}", WsMessageTypes.SERVER_PREVIEW_FRAME, cameraId, frameIdLong);
        } catch (ClientWsInvalidCaptureDescriptorException | ClientWsJsonSerializationException e) {
            log.debug("client_ws preview_frame build failed: {}", e.getMessage());
        } catch (ClientWsSendFailedException e) {
            log.debug("client_ws preview_frame send failed: {}", e.getMessage());
        }
    }

    public void sendPreviewBatch(WebSocket conn, long lineSeq, long serverTsMs, List<PreviewWsFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        try {
            String json = previewJson.buildPreviewBatchJson(lineSeq, serverTsMs, frames);
            WsOutboundJson.sendRaw(conn, json, WsMessageTypes.SERVER_PREVIEW_BATCH);
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
        inspect.sendInspectResult(
                conn, cameraId, productType, detectorId, decision, captureHeader, frameId, inspectionId,
                shmName, heatmapU8Path, heatmapW, heatmapH, currentHttpPath, heatmapArtifactTokenOrNull,
                includeHeatmapFilePathInWs, inspectionArtifactBundleId
        );
    }

    public void sendInspectBucketResult(WebSocket conn, BucketFanOutResult result) {
        inspect.sendInspectBucketResult(conn, result);
    }

    public void sendStreamStarted(WebSocket conn, int cameraId, int maxFps, String httpPath, String mjpegPath) {
        control.sendStreamStarted(conn, cameraId, maxFps, httpPath, mjpegPath);
    }

    public void sendStreamStopped(WebSocket conn, int cameraId) {
        control.sendStreamStopped(conn, cameraId);
    }

    public void sendLightBrightnessAck(
            WebSocket conn,
            JsonNode requestRoot,
            Map<String, Integer> brightnessByEndpoint,
            int defaultBrightnessPercent
    ) {
        control.sendLightBrightnessAck(conn, requestRoot, brightnessByEndpoint, defaultBrightnessPercent);
    }

    public void sendReferenceBundleAck(WebSocket conn, JsonNode requestRoot) {
        control.sendReferenceBundleAck(conn, requestRoot);
    }

    public void sendFpZonesAck(WebSocket conn, JsonNode requestRoot, boolean ok) {
        control.sendFpZonesAck(conn, requestRoot, ok);
    }

    public void sendActiveReferenceViewAck(WebSocket conn, JsonNode requestRoot, int viewIndex) {
        control.sendActiveReferenceViewAck(conn, requestRoot, viewIndex);
    }

    public void sendSessionState(WebSocket conn, ClientWsSessionState state) {
        control.sendSessionState(conn, state);
    }

    public void sendPlcFinsTraffic(WebSocket conn, com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent event) {
        control.sendPlcFinsTraffic(conn, event);
    }

    public void sendError(WebSocket conn, String code, String message) {
        control.sendError(conn, code, message);
    }
}
