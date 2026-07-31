package com.example.iml.orchestrator.integration.clientws;

import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent;
import com.example.iml.orchestrator.integration.preview.PreviewWsFrame;
import org.java_websocket.WebSocket;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Fan-out of outbound WS notify payloads to all open client connections.
 */
final class ClientWsOutboundNotifier {

    private final WsOutboundMessenger outbound;
    private final Supplier<Collection<WebSocket>> connections;

    ClientWsOutboundNotifier(WsOutboundMessenger outbound, Supplier<Collection<WebSocket>> connections) {
        this.outbound = outbound;
        this.connections = connections;
    }

    void notifyPreviewFrame(
            int cameraId,
            String productType,
            String detectorId,
            Map<String, Object> captureHeader,
            String httpPath
    ) {
        if (captureHeader == null || cameraId < 0) {
            return;
        }
        broadcastOpenClients(conn ->
                outbound.sendPreviewFrame(conn, cameraId, productType, detectorId, captureHeader, httpPath));
    }

    void notifyPreviewBatch(long lineSeq, long serverTsMs, List<PreviewWsFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        broadcastOpenClients(conn -> outbound.sendPreviewBatch(conn, lineSeq, serverTsMs, frames));
    }

    void notifyInspectResult(
            int cameraId,
            String productType,
            String detectorId,
            long inspectionId,
            InspectionDecision decision,
            Map<String, Object> captureHeader,
            Path heatmapU8Path,
            int heatmapW,
            int heatmapH,
            String currentHttpPath,
            String heatmapArtifactTokenOrNull,
            boolean includeHeatmapFilePathInWs,
            String inspectionArtifactBundleId
    ) {
        if (captureHeader == null || cameraId < 0) {
            return;
        }
        Object sn = captureHeader.get("shm_name");
        if (sn == null) {
            return;
        }
        String shmName = String.valueOf(sn).trim();
        if (shmName.isEmpty()) {
            return;
        }
        long frameId = YamlScalars.toLong(captureHeader.get("frame_id"), -1L);
        if (frameId < 0) {
            return;
        }
        broadcastOpenClients(conn -> outbound.sendInspectResult(
                conn,
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
        ));
    }

    void notifyInspectBucketResult(BucketFanOutResult result) {
        if (result == null) {
            return;
        }
        broadcastOpenClients(conn -> outbound.sendInspectBucketResult(conn, result));
    }

    void notifyPlcFinsTraffic(PlcFinsTrafficEvent event) {
        if (event == null) {
            return;
        }
        broadcastOpenClients(conn -> outbound.sendPlcFinsTraffic(conn, event));
    }

    void broadcastOpenClients(Consumer<WebSocket> sender) {
        Collection<WebSocket> conns = connections.get();
        if (conns == null || conns.isEmpty()) {
            return;
        }
        for (WebSocket conn : conns) {
            if (conn != null && conn.isOpen()) {
                sender.accept(conn);
            }
        }
    }
}
