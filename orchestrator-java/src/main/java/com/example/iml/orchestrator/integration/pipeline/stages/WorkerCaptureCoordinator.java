package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.spi.CameraCaptureStage;
import com.example.iml.orchestrator.integration.pipeline.spi.CaptureLightingPort;
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

    @Override
    public void saveReferenceCapture(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            BinaryProtocol.Message referenceCapture
    ) {
        jpegWriter.saveCapturedFrame(projectRoot, saveCaptures, referenceCapture.header(), "ref");
    }

    @Override
    public CompletableFuture<PipelineState> scheduleCapture(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            int cameraId,
            ReferenceSnapshot activeReference,
            int flashLeadMs,
            WorkerProcessSupervisor worker,
            CaptureLightingPort lighting,
            ExecutorService captureStageExecutor,
            long triggerSequence,
            String debugLogSuffix
    ) {
        LineSynchronizedCaptureCoordinator lineCapture = lineCaptureCoordinator;
        java.util.concurrent.Executor executor =
                lineCapture != null && lineCapture.isEnabled() && triggerSequence > 0L
                        ? Runnable::run
                        : captureStageExecutor;
        return CompletableFuture.supplyAsync(
                () -> runCaptureSync(
                        projectRoot,
                        saveCaptures,
                        cameraId,
                        activeReference,
                        flashLeadMs,
                        worker,
                        lighting,
                        triggerSequence,
                        debugLogSuffix
                ),
                executor
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
            CaptureLightingPort lighting,
            long triggerSequence,
            String debugLogSuffix
    ) {
        return WorkerCaptureSyncRunner.run(
                log,
                jpegWriter,
                captureWithoutReference,
                cameraStreamService,
                lineCaptureCoordinator,
                this::maybeDownscaleInspectionCapture,
                projectRoot,
                saveCaptures,
                cameraId,
                activeReference,
                flashLeadMs,
                worker,
                lighting,
                triggerSequence,
                debugLogSuffix
        );
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
