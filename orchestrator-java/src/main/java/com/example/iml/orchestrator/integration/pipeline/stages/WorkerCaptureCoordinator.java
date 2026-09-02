package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.diagnostics.CaptureSyncDiagnostics;
import com.example.iml.orchestrator.integration.diagnostics.TwoPhaseCaptureDiagnostics;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.spi.CameraCaptureStage;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Стадия захвата кадра с воркера камеры (подсветка, опциональная задержка вспышки, JPEG для save_captures).
 */
public final class WorkerCaptureCoordinator implements CameraCaptureStage {

    private final Logger log;
    private final FrameJpegWriter jpegWriter;
    private final CaptureFrameDownscaleService captureDownscale;
    private final boolean downscaleInspectionCapture;
    private final boolean downscaleReferenceCapture;
    private final boolean downscaleClientReferenceBundle;
    private final boolean captureWithoutReference;
    private volatile CameraStreamService cameraStreamService;
    private volatile LineSynchronizedCaptureCoordinator lineCaptureCoordinator;
    private volatile TwoPhaseCaptureDiagnostics twoPhaseCaptureDiagnostics;

    public WorkerCaptureCoordinator(
            Logger log,
            FrameJpegWriter jpegWriter,
            CaptureFrameDownscaleService captureDownscale,
            boolean downscaleInspectionCapture,
            boolean downscaleReferenceCapture,
            boolean downscaleClientReferenceBundle,
            boolean captureWithoutReference
    ) {
        this.log = log;
        this.jpegWriter = jpegWriter;
        this.captureDownscale = captureDownscale;
        this.downscaleInspectionCapture = downscaleInspectionCapture;
        this.downscaleReferenceCapture = downscaleReferenceCapture;
        this.downscaleClientReferenceBundle = downscaleClientReferenceBundle;
        this.captureWithoutReference = captureWithoutReference;
    }

    public void setCameraStreamService(CameraStreamService cameraStreamService) {
        this.cameraStreamService = cameraStreamService;
    }

    public void setLineCaptureCoordinator(LineSynchronizedCaptureCoordinator lineCaptureCoordinator) {
        this.lineCaptureCoordinator = lineCaptureCoordinator;
    }

    public void setTwoPhaseCaptureDiagnostics(TwoPhaseCaptureDiagnostics twoPhaseCaptureDiagnostics) {
        this.twoPhaseCaptureDiagnostics = twoPhaseCaptureDiagnostics;
    }

    @Override
    public CompletableFuture<PipelineState> scheduleCapture(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            int cameraId,
            ReferenceSnapshot activeReference,
            int flashLeadMs,
            WorkerProcessSupervisor worker,
            LightTriggerClient lightClient,
            ExecutorService captureStageExecutor,
            long triggerSequence,
            String debugLogSuffix,
            int phaseId,
            long parentCycleId,
            long rawTriggerSequence
    ) {
        LineSynchronizedCaptureCoordinator lineCapture = lineCaptureCoordinator;
        if (lineCapture != null && lineCapture.isEnabled() && triggerSequence > 0L) {
            return CompletableFuture.supplyAsync(
                    () -> runCaptureSync(
                            projectRoot,
                            saveCaptures,
                            cameraId,
                            activeReference,
                            flashLeadMs,
                            worker,
                            lightClient,
                            triggerSequence,
                            debugLogSuffix,
                            phaseId,
                            parentCycleId,
                            rawTriggerSequence
                    ),
                    Runnable::run
            );
        }
        return CompletableFuture.supplyAsync(
                () -> runCaptureSync(
                        projectRoot,
                        saveCaptures,
                        cameraId,
                        activeReference,
                        flashLeadMs,
                        worker,
                        lightClient,
                        triggerSequence,
                        debugLogSuffix,
                        phaseId,
                        parentCycleId,
                        rawTriggerSequence
                ),
                captureStageExecutor
        );
    }

    @Override
    public PipelineState runCaptureSync(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            int cameraId,
            ReferenceSnapshot activeReference,
            int flashLeadMs,
            WorkerProcessSupervisor worker,
            LightTriggerClient lightClient,
            long triggerSequence,
            String debugLogSuffix,
            int phaseId,
            long parentCycleId,
            long rawTriggerSequence
    ) {
        try {
            if (activeReference == null || activeReference.header() == null) {
                if (!captureWithoutReference) {
                    throw new IllegalStateException(
                            "no reference snapshot; wait for reference bootstrap or send client.reference_bundle");
                }
            }
            CameraStreamService streams = cameraStreamService;
            if (streams != null && streams.isStreaming(cameraId)) {
                throw new IllegalStateException(
                        "camera " + cameraId + " client stream is active; send client.stream_stop before inspection capture");
            }
            long t0 = System.nanoTime();
            long refFrameId = activeReference != null && activeReference.header() != null
                    ? YamlScalars.toLong(activeReference.header().get("frame_id"), -1L)
                    : -1L;
            final BinaryProtocol.Message[] captureHolder = new BinaryProtocol.Message[1];
            LineSynchronizedCaptureCoordinator lineCapture = lineCaptureCoordinator;
            TwoPhaseCaptureDiagnostics phaseCaptureDiagnostics = twoPhaseCaptureDiagnostics;
            lightClient.runCaptureWithLighting(cameraId, refFrameId, "capture", flashLeadMs, () -> {
                try {
                    if (phaseCaptureDiagnostics != null) {
                        phaseCaptureDiagnostics.onWaitFrameStart(
                                cameraId,
                                phaseId,
                                parentCycleId,
                                rawTriggerSequence
                        );
                    }
                    if (lineCapture != null && lineCapture.isEnabled() && triggerSequence > 0L) {
                        captureHolder[0] = lineCapture.captureForLine(triggerSequence, cameraId, worker);
                    } else {
                        captureHolder[0] = worker.command(Map.of("op", "capture", "sync", true));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            BinaryProtocol.Message capture = captureHolder[0];
            if (!hasUsableCaptureHeader(capture)) {
                boolean lineMode = lineCapture != null && lineCapture.isEnabled() && triggerSequence > 0L;
                if (!lineMode) {
                    capture = worker.command(Map.of("op", "capture", "sync", true));
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "worker cam={} fallback capture (line/sync response had no usable frame header)",
                                cameraId
                        );
                    }
                } else {
                    log.warn(
                            "worker cam={} line capture seq={} had no usable frame — skip sync fallback to avoid double exposure",
                            cameraId,
                            triggerSequence
                    );
                }
            }
            if (!hasUsableCaptureHeader(capture)) {
                String detail = capture == null || capture.header() == null
                        ? "null capture response"
                        : String.valueOf(capture.header().getOrDefault("error", capture.header()));
                long orchMs = YamlScalars.nanosToMs(System.nanoTime() - t0);
                if (phaseCaptureDiagnostics != null) {
                    phaseCaptureDiagnostics.onCaptureFail(
                            cameraId,
                            phaseId,
                            parentCycleId,
                            rawTriggerSequence,
                            detail,
                            orchMs
                    );
                } else {
                    CaptureSyncDiagnostics.logInspectCaptureFail(log, cameraId, detail, orchMs);
                }
                log.warn(
                        "worker cam={} capture unusable after line/sync+fallback ({}); geometry/python will be skipped",
                        cameraId,
                        detail
                );
            } else {
                Map<String, Object> header = capture.header();
                long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
                long orchMs = YamlScalars.nanosToMs(System.nanoTime() - t0);
                long workerLatencyNs = YamlScalars.toLong(header.get("capture_latency_ns"), 0L);
                if (phaseCaptureDiagnostics != null) {
                    phaseCaptureDiagnostics.onCaptureOk(
                            cameraId,
                            phaseId,
                            parentCycleId,
                            rawTriggerSequence,
                            frameId,
                            orchMs,
                            workerLatencyNs / 1_000_000L
                    );
                } else {
                    CaptureSyncDiagnostics.logInspectCapture(
                            log,
                            cameraId,
                            frameId,
                            orchMs,
                            YamlScalars.toLong(header.get("capture_started_ns"), 0L),
                            workerLatencyNs
                    );
                }
            }
            jpegWriter.saveCapturedFrame(projectRoot, saveCaptures, capture.header(), "cap");
            if (log.isDebugEnabled()) {
                log.debug("worker cam={} {} header={}", cameraId, debugLogSuffix, capture.header());
            }
            capture = maybeDownscaleInspectionCapture(capture, cameraId);
            return new PipelineState(capture, null, null, YamlScalars.nanosToMs(System.nanoTime() - t0), 0L, 0L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void saveReferenceCapture(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            BinaryProtocol.Message referenceCapture
    ) {
        jpegWriter.saveCapturedFrame(projectRoot, saveCaptures, referenceCapture.header(), "ref");
    }

    private static boolean hasUsableCaptureHeader(BinaryProtocol.Message capture) {
        if (capture == null || capture.header() == null) {
            return false;
        }
        Map<String, Object> h = capture.header();
        String shmName = String.valueOf(h.getOrDefault("shm_name", "")).trim();
        int width = YamlScalars.toInt(h.get("width"), 0);
        int height = YamlScalars.toInt(h.get("height"), 0);
        long frameId = YamlScalars.toLong(h.get("frame_id"), -1L);
        return !shmName.isEmpty() && width > 0 && height > 0 && frameId >= 0L;
    }

    @Override
    public BinaryProtocol.Message maybeDownscaleInspectionCapture(
            BinaryProtocol.Message capture,
            int cameraId
    ) {
        if (!downscaleInspectionCapture || captureDownscale == null) {
            return capture;
        }
        return captureDownscale.downscaleCapture(capture, cameraId, "inspect");
    }

    @Override
    public Map<String, Object> maybeDownscaleReferenceHeader(
            Map<String, Object> referenceHeader,
            int cameraId
    ) {
        if (!downscaleReferenceCapture || captureDownscale == null || referenceHeader == null) {
            return referenceHeader;
        }
        return captureDownscale.downscaleHeader(referenceHeader, cameraId, "reference");
    }

    @Override
    public Map<String, Object> maybeDownscaleClientReferenceHeader(
            Map<String, Object> referenceHeader,
            int cameraId
    ) {
        if (!downscaleClientReferenceBundle || captureDownscale == null || referenceHeader == null) {
            return referenceHeader;
        }
        return captureDownscale.downscaleHeader(referenceHeader, cameraId, "client_reference");
    }
}
