package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
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

    public WorkerCaptureCoordinator(Logger log, FrameJpegWriter jpegWriter) {
        this.log = log;
        this.jpegWriter = jpegWriter;
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
            LightTriggerClient lightClient,
            ExecutorService captureStageExecutor,
            String debugLogSuffix
    ) {
        return CompletableFuture.supplyAsync(
                () -> runCaptureSync(
                        projectRoot,
                        saveCaptures,
                        cameraId,
                        activeReference,
                        flashLeadMs,
                        worker,
                        lightClient,
                        debugLogSuffix
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
            String debugLogSuffix
    ) {
        try {
            long t0 = System.nanoTime();
            lightClient.trigger(cameraId, YamlScalars.toLong(activeReference.header().get("frame_id"), -1L), "capture");
            if (flashLeadMs > 0) {
                Thread.sleep(flashLeadMs);
            }
            BinaryProtocol.Message capture = worker.command(Map.of("op", "capture"));
            jpegWriter.saveCapturedFrame(projectRoot, saveCaptures, capture.header(), "cap");
            if (log.isDebugEnabled()) {
                log.debug("worker cam={} {} header={}", cameraId, debugLogSuffix, capture.header());
            }
            return new PipelineState(capture, null, null, YamlScalars.nanosToMs(System.nanoTime() - t0), 0L, 0L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
