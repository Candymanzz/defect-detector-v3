package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.diagnostics.CaptureSyncDiagnostics;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.stream.StreamException;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Capture + publish helpers for {@link LivePreviewLineBatchTicker}. */
final class LivePreviewLineBatchCaptureSupport {

    private LivePreviewLineBatchCaptureSupport() {
    }

    static Map<Integer, BinaryProtocol.Message> capturePreviewLineBatch(
            LivePreviewConfig cfg,
            LightTriggerClient lightClient,
            int flashLeadMs,
            LineSynchronizedCaptureCoordinator lineCapture,
            long lineSeq,
            Map<Integer, WorkerProcessSupervisor> workersByCamera
    ) throws StreamException {
        if (cfg.flashOnTick()) {
            int flashCameraId = workersByCamera.keySet().stream().sorted().findFirst().orElse(0);
            AtomicReference<Map<Integer, BinaryProtocol.Message>> capturedHolder = new AtomicReference<>();
            lightClient.runCaptureWithLighting(flashCameraId, -1L, "preview", flashLeadMs, () -> {
                try {
                    capturedHolder.set(lineCapture.captureLineBatch(lineSeq, workersByCamera, true));
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new com.example.iml.orchestrator.integration.capture.CaptureException(e);
                }
            });
            return capturedHolder.get();
        }
        return lineCapture.captureLineBatch(lineSeq, workersByCamera, true);
    }

    static Map<Integer, BinaryProtocol.Message> capturePreviewSoloSequential(
            List<CameraPreviewTarget> activeTargets,
            Logger log
    ) {
        Map<Integer, BinaryProtocol.Message> captured = new LinkedHashMap<>();
        for (CameraPreviewTarget target : activeTargets) {
            int cameraId = target.cameraId();
            try {
                BinaryProtocol.Message message;
                synchronized (target.worker()) {
                    message = target.worker().command(Map.of("op", "capture", "sync", true));
                }
                if (LivePreviewPerCameraTicker.hasUsableCaptureHeader(message)) {
                    captured.put(cameraId, message);
                }
            } catch (Exception e) {
                log.warn("live_preview solo cam={}: {}", cameraId, e.getMessage());
            }
        }
        return captured;
    }

    static void recordCaptures(
            CaptureSyncDiagnostics syncDiag,
            long round,
            long captureStartedNs,
            List<CameraPreviewTarget> activeTargets,
            Map<Integer, BinaryProtocol.Message> captured
    ) {
        for (CameraPreviewTarget target : activeTargets) {
            BinaryProtocol.Message message = captured.get(target.cameraId());
            if (!LivePreviewPerCameraTicker.hasUsableCaptureHeader(message)) {
                syncDiag.recordCaptureFail(round, target.cameraId(), "missing after batch",
                        LivePreviewPerCameraTicker.elapsedMs(captureStartedNs));
                continue;
            }
            Map<String, Object> header = message.header();
            syncDiag.recordCaptureOk(
                    round,
                    target.cameraId(),
                    YamlScalars.toLong(header.get("frame_id"), -1L),
                    YamlScalars.toLong(header.get("capture_started_ns"), 0L),
                    YamlScalars.toLong(header.get("capture_latency_ns"), 0L),
                    LivePreviewPerCameraTicker.elapsedMs(captureStartedNs)
            );
        }
    }

    static void publishPreviewCaptures(
            CaptureSyncDiagnostics syncDiag,
            LivePreviewTickPolicy policy,
            LivePreviewJpegPublisher jpegPublisher,
            LivePreviewWsNotifier wsNotifier,
            LivePreviewRuntimeContext context,
            long round,
            long lineSeq,
            List<CameraPreviewTarget> activeTargets,
            Map<Integer, BinaryProtocol.Message> captured,
            Logger log
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
            LivePreviewMetrics.CameraMetrics metrics = context.metrics.forCamera(cameraId);
            Map<String, Object> header = capture.header();
            long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
            String shmName = String.valueOf(header.get("shm_name"));
            int width = YamlScalars.toInt(header.get("width"), 0);
            int height = YamlScalars.toInt(header.get("height"), 0);
            String httpPath = null;
            if (imagesEnabled) {
                long encodeStarted = System.nanoTime();
                LivePreviewJpegPublisher.JpegArtifact jpeg = jpegPublisher.writePreviewJpeg(
                        cameraId, shmName, width, height,
                        YamlScalars.toInt(header.get("stride"), 0),
                        YamlScalars.toLong(header.get("shm_offset"), 0L));
                metrics.encodeNs.add(System.nanoTime() - encodeStarted);
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
            wsFrames.add(new PreviewWsFrame(
                    cameraId, target.productType(), target.detectorId(), header, httpPath));
        }
        if (!wsFrames.isEmpty()) {
            wsNotifier.notifyPreviewBatch(round, lineSeq, serverTsMs, wsFrames);
        }
    }
}
