package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.diagnostics.CaptureSyncDiagnostics;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class LivePreviewLineBatchTicker {
    private final Logger log;
    private final LivePreviewConfig cfg;
    private final LightTriggerClient lightClient;
    private final int flashLeadMs;
    private final CaptureSyncDiagnostics syncDiag;
    private final LivePreviewRuntimeContext context;
    private final LivePreviewTickPolicy policy;
    private final LivePreviewJpegPublisher jpegPublisher;
    private final LivePreviewWsNotifier wsNotifier;
    private final List<CameraPreviewTarget> previewTargets;

    LivePreviewLineBatchTicker(
            Logger log,
            LivePreviewConfig cfg,
            LightTriggerClient lightClient,
            int flashLeadMs,
            CaptureSyncDiagnostics syncDiag,
            LivePreviewRuntimeContext context,
            LivePreviewTickPolicy policy,
            LivePreviewJpegPublisher jpegPublisher,
            LivePreviewWsNotifier wsNotifier,
            List<CameraPreviewTarget> previewTargets
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
        this.previewTargets = previewTargets;
    }

    void tickLineBatchGuarded(long lineSeq, LineSynchronizedCaptureCoordinator lineCapture) {
        if (context.closed.get() || policy.isPreviewPaused()) {
            return;
        }
        if (policy.hasAnyInspectionInFlight()) {
            for (CameraPreviewTarget target : previewTargets) {
                context.metrics.forCamera(target.cameraId()).droppedTicks.increment();
            }
            return;
        }
        List<Integer> cameraIds = previewTargets.stream()
                .map(CameraPreviewTarget::cameraId)
                .collect(Collectors.toList());
        long round = syncDiag.beginRound(cameraIds);
        List<CameraPreviewTarget> activeTargets = new ArrayList<>();
        for (CameraPreviewTarget target : previewTargets) {
            int cameraId = target.cameraId();
            if (policy.isInspectionInFlight(cameraId)) {
                syncDiag.recordCaptureSkipped(round, cameraId, "inspection_in_flight");
                context.metrics.forCamera(cameraId).droppedTicks.increment();
                continue;
            }
            if (policy.isStreaming(cameraId)) {
                syncDiag.recordCaptureSkipped(round, cameraId, "client_stream_active");
                continue;
            }
            activeTargets.add(target);
        }
        if (activeTargets.isEmpty()) {
            return;
        }
        long captureStartedNs = System.nanoTime();
        try {
            Map<Integer, WorkerProcessSupervisor> workersByCamera = new LinkedHashMap<>();
            for (CameraPreviewTarget target : activeTargets) {
                workersByCamera.put(target.cameraId(), target.worker());
            }
            Map<Integer, BinaryProtocol.Message> captured;
            try {
                captured = LivePreviewLineBatchCaptureSupport.capturePreviewLineBatch(
                        cfg, lightClient, flashLeadMs, lineCapture, lineSeq, workersByCamera);
            } catch (Exception batchError) {
                log.warn("live_preview line batch failed: {}", batchError.getMessage());
                captured = LivePreviewLineBatchCaptureSupport.capturePreviewSoloSequential(activeTargets, log);
            }
            if (captured == null || captured.isEmpty()) {
                log.warn("live_preview: no usable frames after line batch");
                return;
            }
            LivePreviewLineBatchCaptureSupport.recordCaptures(
                    syncDiag, round, captureStartedNs, activeTargets, captured);
            LivePreviewLineBatchCaptureSupport.publishPreviewCaptures(
                    syncDiag, policy, jpegPublisher, wsNotifier, context,
                    round, lineSeq, activeTargets, captured, log);
        } catch (Exception e) {
            log.warn("live_preview tick failed: {}", e.getMessage());
        } finally {
            for (CameraPreviewTarget target : activeTargets) {
                context.metrics.forCamera(target.cameraId()).maybeLog(log, target.cameraId());
            }
        }
    }
}
