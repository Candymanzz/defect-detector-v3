package com.example.iml.orchestrator.integration.clientws.outbound;

import com.example.iml.orchestrator.integration.clientws.exception.ClientWsInvalidCaptureDescriptorException;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsJsonSerializationException;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsSendFailedException;
import com.example.iml.orchestrator.integration.clientws.outbound.messages.InspectOutboundJson;
import com.example.iml.orchestrator.integration.clientws.protocol.WsMessageTypes;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;

import java.nio.file.Path;
import java.util.Map;

/** Inspect-result outbound WebSocket sends. */
final class WsInspectOutboundMessenger {

    private final Logger log;
    private final InspectOutboundJson inspectJson;

    WsInspectOutboundMessenger(Logger log, InspectOutboundJson inspectJson) {
        this.log = log;
        this.inspectJson = inspectJson;
    }

    void sendInspectResult(
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
            String json = inspectJson.buildInspectResultJson(
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
            WsOutboundJson.sendRaw(conn, json, WsMessageTypes.SERVER_INSPECT_RESULT);
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

    void sendInspectBucketResult(WebSocket conn, BucketFanOutResult result) {
        try {
            WsOutboundJson.sendRaw(conn, inspectJson.buildInspectBucketResultJson(result), WsMessageTypes.SERVER_INSPECT_BUCKET_RESULT);
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
}
