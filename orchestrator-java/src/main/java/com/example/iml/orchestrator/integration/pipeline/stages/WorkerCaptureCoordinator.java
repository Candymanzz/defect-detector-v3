package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;
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
    private volatile CameraStreamService cameraStreamService;

    public WorkerCaptureCoordinator(Logger log, FrameJpegWriter jpegWriter) {
        this.log = log;
        this.jpegWriter = jpegWriter;
    }

    public void setCameraStreamService(CameraStreamService cameraStreamService) {
        this.cameraStreamService = cameraStreamService;
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
            if (activeReference == null || activeReference.header() == null) {
                throw new IllegalStateException("no reference snapshot; wait for reference bootstrap or send client.reference_bundle");
            }
            CameraStreamService streams = cameraStreamService;
            if (streams != null && streams.isStreaming(cameraId)) {
                throw new IllegalStateException(
                        "camera " + cameraId + " client stream is active; send client.stream_stop before inspection capture");
            }
            long t0 = System.nanoTime();
            long refFrameId = YamlScalars.toLong(activeReference.header().get("frame_id"), -1L);
            final BinaryProtocol.Message[] captureHolder = new BinaryProtocol.Message[1];
            lightClient.runCaptureWithLighting(cameraId, refFrameId, "capture", flashLeadMs, () -> {
                captureHolder[0] = worker.command(Map.of("op", "capture", "sync", true));
            });
            BinaryProtocol.Message capture = captureHolder[0];
            if (!hasUsableCaptureHeader(capture)) {
                // Some worker/backends can return an ACK-like response for sync capture in continuous mode.
                // Fallback to regular capture to obtain shm_name/width/height needed by geometry/python stages.
                capture = worker.command(Map.of("op", "capture"));
                if (log.isDebugEnabled()) {
                    log.debug("worker cam={} fallback capture (sync response had no usable frame header)", cameraId);
                }
            }
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

    private static boolean hasUsableCaptureHeader(BinaryProtocol.Message capture) {
        if (capture == null || capture.header() == null) {
            return false;
        }
        Map<String, Object> h = capture.header();
        String shmName = String.valueOf(h.getOrDefault("shm_name", "")).trim();
        int width = YamlScalars.toInt(h.get("width"), 0);
        int height = YamlScalars.toInt(h.get("height"), 0);
        return !shmName.isEmpty() && width > 0 && height > 0;
    }
}
