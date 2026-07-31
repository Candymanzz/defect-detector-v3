package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.diagnostics.CaptureSyncDiagnostics;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

final class LivePreviewWsNotifier {
    private final Logger log;
    private final ClientWebSocketServer clientWs;
    private final CaptureSyncDiagnostics syncDiag;
    private final LivePreviewMetrics metrics;

    LivePreviewWsNotifier(
            Logger log,
            ClientWebSocketServer clientWs,
            CaptureSyncDiagnostics syncDiag,
            LivePreviewMetrics metrics
    ) {
        this.log = log;
        this.clientWs = clientWs;
        this.syncDiag = syncDiag;
        this.metrics = metrics;
    }

    void notifyPreviewFrame(
            long round,
            CameraPreviewTarget target,
            Map<String, Object> header,
            String httpPath,
            long frameId
    ) {
        if (clientWs == null) {
            return;
        }
        LivePreviewMetrics.CameraMetrics cameraMetrics = metrics.forCamera(target.cameraId());
        long wsStarted = System.nanoTime();
        clientWs.notifyPreviewFrame(
                target.cameraId(), target.productType(), target.detectorId(), header, httpPath);
        cameraMetrics.wsNs.add(System.nanoTime() - wsStarted);
        cameraMetrics.frames.increment();
        syncDiag.recordWsSend(round, target.cameraId(), frameId);
    }

    void notifyPreviewBatch(long round, long lineSeq, long serverTsMs, List<PreviewWsFrame> frames) {
        if (clientWs == null || frames.isEmpty()) {
            return;
        }
        long wsStarted = System.nanoTime();
        clientWs.notifyPreviewBatch(lineSeq, serverTsMs, frames);
        long perCameraWsNs = (System.nanoTime() - wsStarted) / Math.max(1, frames.size());
        for (PreviewWsFrame frame : frames) {
            LivePreviewMetrics.CameraMetrics cameraMetrics = metrics.forCamera(frame.cameraId());
            cameraMetrics.wsNs.add(perCameraWsNs);
            cameraMetrics.frames.increment();
            long frameId = YamlScalars.toLong(frame.captureHeader().get("frame_id"), -1L);
            syncDiag.recordWsSend(round, frame.cameraId(), frameId);
        }
        log.info("live_preview batch published line_seq={} cameras={}", lineSeq, frames.size());
    }
}
