package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.diagnostics.CaptureSyncDiagnostics;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class LivePreviewBatchPublisher {
    private LivePreviewBatchPublisher() {
    }

    static void publish(
            Logger log, LivePreviewTickPolicy policy, LivePreviewMetrics metrics, CaptureSyncDiagnostics syncDiag,
            LivePreviewJpegPublisher jpegPublisher, LivePreviewWsNotifier wsNotifier, long round, long lineSeq,
            List<CameraPreviewTarget> activeTargets, Map<Integer, BinaryProtocol.Message> captured
    ) {
        boolean imagesEnabled = policy.areImagesEnabled();
        long serverTsMs = System.currentTimeMillis();
        List<PreviewWsFrame> wsFrames = new ArrayList<>(captured.size());
        for (CameraPreviewTarget target : activeTargets) {
            int cameraId = target.cameraId();
            BinaryProtocol.Message capture = captured.get(cameraId);
            if (!LivePreviewPerCameraTicker.hasUsableCaptureHeader(capture)) {
                continue;
            }
            Map<String, Object> header = capture.header();
            long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
            String shmName = String.valueOf(header.get("shm_name"));
            int width = YamlScalars.toInt(header.get("width"), 0);
            int height = YamlScalars.toInt(header.get("height"), 0);
            String httpPath = null;
            if (imagesEnabled) {
                long encodeStarted = System.nanoTime();
                LivePreviewJpegPublisher.JpegArtifact jpeg = jpegPublisher.writePreviewJpeg(cameraId, shmName, width, height,
                        YamlScalars.toInt(header.get("stride"), 0), YamlScalars.toLong(header.get("shm_offset"), 0L));
                metrics.forCamera(cameraId).encodeNs.add(System.nanoTime() - encodeStarted);
                if (jpeg.path() == null || !Files.isRegularFile(jpeg.path())) {
                    if (jpeg.error() != null) {
                        syncDiag.recordCaptureFail(round, cameraId, "jpeg: " + jpeg.error(), 0L);
                        log.warn("live_preview cam={} frame={}: {}", cameraId, frameId, jpeg.error());
                    }
                    continue;
                }
                jpegPublisher.updateUi(target, frameId, shmName, width, height, jpeg);
                httpPath = "/api/camera/" + cameraId + "/current.jpg";
            }
            wsFrames.add(new PreviewWsFrame(cameraId, target.productType(), target.detectorId(), header, httpPath));
        }
        if (!wsFrames.isEmpty()) {
            wsNotifier.notifyPreviewBatch(round, lineSeq, serverTsMs, wsFrames);
        }
    }
}
