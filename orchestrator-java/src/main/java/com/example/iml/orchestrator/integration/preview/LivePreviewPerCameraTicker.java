package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.stream.StreamException;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.diagnostics.CaptureSyncDiagnostics;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class LivePreviewPerCameraTicker {
    private final Logger log;
    private final LivePreviewConfig cfg;
    private final LightTriggerClient lightClient;
    private final int flashLeadMs;
    private final CaptureSyncDiagnostics syncDiag;
    private final LivePreviewRuntimeContext context;
    private final LivePreviewTickPolicy policy;
    private final LivePreviewJpegPublisher jpegPublisher;
    private final LivePreviewWsNotifier wsNotifier;

    LivePreviewPerCameraTicker(
            Logger log,
            LivePreviewConfig cfg,
            LightTriggerClient lightClient,
            int flashLeadMs,
            CaptureSyncDiagnostics syncDiag,
            LivePreviewRuntimeContext context,
            LivePreviewTickPolicy policy,
            LivePreviewJpegPublisher jpegPublisher,
            LivePreviewWsNotifier wsNotifier
    ) {
        this.log = log;
        this.cfg = cfg;
        this.lightClient = lightClient;
        this.flashLeadMs = flashLeadMs;
        this.syncDiag = syncDiag;
        this.context = context;
        this.policy = policy;
        this.jpegPublisher = jpegPublisher;
        this.wsNotifier = wsNotifier;
    }

    void tickGuarded(long round, CameraPreviewTarget target) {
        int cameraId = target.cameraId();
        AtomicBoolean inProgress = context.tickInProgressByCamera.computeIfAbsent(
                cameraId, ignored -> new AtomicBoolean(false));
        LivePreviewMetrics.CameraMetrics metrics = context.metrics.forCamera(cameraId);
        if (!inProgress.compareAndSet(false, true)) {
            metrics.droppedTicks.increment();
            metrics.maybeLog(log, cameraId);
            return;
        }
        try {
            tick(round, target, metrics);
        } finally {
            inProgress.set(false);
            metrics.maybeLog(log, cameraId);
        }
    }

    private void tick(long round, CameraPreviewTarget target, LivePreviewMetrics.CameraMetrics metrics) {
        int cameraId = target.cameraId();
        if (context.closed.get()) {
            return;
        }
        if (policy.isPreviewPaused()) {
            syncDiag.recordCaptureSkipped(round, cameraId, "preview_paused");
            return;
        }
        // Capture reuses the camera SHM buffer, so preview must not overwrite
        // pixels while an inspection stage is still reading them.
        if (policy.isInspectionInFlight(cameraId) || policy.hasAnyInspectionInFlight()) {
            metrics.droppedTicks.increment();
            syncDiag.recordCaptureSkipped(round, cameraId, "inspection_in_flight");
            return;
        }
        if (policy.isStreaming(cameraId)) {
            syncDiag.recordCaptureSkipped(round, cameraId, "client_stream_active");
            return;
        }
        long captureStartedNs = System.nanoTime();
        try {
            BinaryProtocol.Message capture = capture(target.worker(), cameraId);
            if (capture == null || capture.header() == null) {
                syncDiag.recordCaptureFail(
                        round, cameraId, capture == null ? "null message" : "message without header",
                        elapsedMs(captureStartedNs));
                log.warn("live_preview cam={}: capture returned {}", cameraId,
                        capture == null ? "null message" : "message without header");
                return;
            }
            Map<String, Object> header = capture.header();
            long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
            if (frameId < 0) {
                syncDiag.recordCaptureFail(round, cameraId,
                        "invalid frame_id: " + header.get("frame_id"), elapsedMs(captureStartedNs));
                log.warn("live_preview cam={}: invalid frame_id in capture header: {}",
                        cameraId, header.get("frame_id"));
                return;
            }
            String shmName = String.valueOf(header.get("shm_name"));
            int width = YamlScalars.toInt(header.get("width"), 0);
            int height = YamlScalars.toInt(header.get("height"), 0);
            int stride = YamlScalars.toInt(header.get("stride"), 0);
            if (shmName.isBlank() || width <= 0 || height <= 0) {
                syncDiag.recordCaptureFail(round, cameraId,
                        "invalid geometry shm=" + shmName + " w=" + width + " h=" + height,
                        elapsedMs(captureStartedNs));
                log.warn(
                        "live_preview cam={}: invalid capture geometry frame={} shm='{}' width={} height={} stride={}",
                        cameraId, frameId, shmName, width, height, stride);
                return;
            }
            syncDiag.recordCaptureOk(
                    round, cameraId, frameId,
                    YamlScalars.toLong(header.get("capture_started_ns"), 0L),
                    YamlScalars.toLong(header.get("capture_latency_ns"), 0L),
                    elapsedMs(captureStartedNs));
            if (!policy.areImagesEnabled()) {
                wsNotifier.notifyPreviewFrame(round, target, header, null, frameId);
                return;
            }
            long encodeStarted = System.nanoTime();
            LivePreviewJpegPublisher.JpegArtifact jpeg = jpegPublisher.writePreviewJpeg(
                    cameraId, shmName, width, height, stride,
                    YamlScalars.toLong(header.get("shm_offset"), 0L));
            metrics.encodeNs.add(System.nanoTime() - encodeStarted);
            if (jpeg.path() == null || !Files.isRegularFile(jpeg.path())) {
                if (jpeg.error() != null) {
                    syncDiag.recordCaptureFail(
                            round, cameraId, "jpeg: " + jpeg.error(), elapsedMs(captureStartedNs));
                    log.warn("live_preview cam={} frame={}: {}", cameraId, frameId, jpeg.error());
                }
                return;
            }
            jpegPublisher.updateUi(target, frameId, shmName, width, height, jpeg);
            wsNotifier.notifyPreviewFrame(
                    round, target, header, "/api/camera/" + cameraId + "/current.jpg", frameId);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                syncDiag.recordCaptureFail(round, cameraId, "interrupted", elapsedMs(captureStartedNs));
                return;
            }
            syncDiag.recordCaptureFail(round, cameraId, e.getMessage(), elapsedMs(captureStartedNs));
            log.debug("live_preview cam={}: {}", cameraId, e.getMessage());
        }
    }

    private BinaryProtocol.Message capture(WorkerProcessSupervisor worker, int cameraId) throws StreamException {
        return LivePreviewCaptureSupport.capture(worker, cameraId, cfg, lightClient, flashLeadMs, log);
    }

    static boolean hasUsableCaptureHeader(BinaryProtocol.Message capture) {
        return LivePreviewCaptureSupport.hasUsableCaptureHeader(capture);
    }

    static long elapsedMs(long startedNs) {
        return LivePreviewCaptureSupport.elapsedMs(startedNs);
    }
}
