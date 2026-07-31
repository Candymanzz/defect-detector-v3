package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.pipeline.PipelineException;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.diagnostics.CaptureSyncDiagnostics;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.spi.CaptureLightingPort;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiFunction;

/** Synchronous worker capture path used by {@link WorkerCaptureCoordinator}. */
final class WorkerCaptureSyncRunner {

    private WorkerCaptureSyncRunner() {
    }

    static PipelineState run(
            Logger log,
            FrameJpegWriter jpegWriter,
            boolean captureWithoutReference,
            CameraStreamService cameraStreamService,
            LineSynchronizedCaptureCoordinator lineCaptureCoordinator,
            BiFunction<BinaryProtocol.Message, Integer, BinaryProtocol.Message> maybeDownscale,
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
        try {
            if (activeReference == null || activeReference.header() == null) {
                if (!captureWithoutReference) {
                    throw new IllegalStateException(
                            "no reference snapshot; wait for reference bootstrap or send client.reference_bundle");
                }
            }
            if (cameraStreamService != null && cameraStreamService.isStreaming(cameraId)) {
                throw new IllegalStateException(
                        "camera " + cameraId + " client stream is active; send client.stream_stop before inspection capture");
            }
            long t0 = System.nanoTime();
            long refFrameId = activeReference != null && activeReference.header() != null
                    ? YamlScalars.toLong(activeReference.header().get("frame_id"), -1L)
                    : -1L;
            final BinaryProtocol.Message[] captureHolder = new BinaryProtocol.Message[1];
            LineSynchronizedCaptureCoordinator lineCapture = lineCaptureCoordinator;
            lighting.runCaptureWithLighting(cameraId, refFrameId, "capture", flashLeadMs, () -> {
                try {
                    if (lineCapture != null && lineCapture.isEnabled() && triggerSequence > 0L) {
                        captureHolder[0] = lineCapture.captureForLine(triggerSequence, cameraId, worker);
                    } else {
                        captureHolder[0] = worker.command(Map.of("op", "capture", "sync", true));
                    }
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new PipelineException(e);
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
                CaptureSyncDiagnostics.logInspectCaptureFail(
                        log,
                        cameraId,
                        detail,
                        YamlScalars.nanosToMs(System.nanoTime() - t0)
                );
                log.warn(
                        "worker cam={} capture unusable after line/sync+fallback ({}); geometry/python will be skipped",
                        cameraId,
                        detail
                );
            } else {
                Map<String, Object> header = capture.header();
                long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
                long orchMs = YamlScalars.nanosToMs(System.nanoTime() - t0);
                CaptureSyncDiagnostics.logInspectCapture(
                        log,
                        cameraId,
                        frameId,
                        orchMs,
                        YamlScalars.toLong(header.get("capture_started_ns"), 0L),
                        YamlScalars.toLong(header.get("capture_latency_ns"), 0L)
                );
            }
            jpegWriter.saveCapturedFrame(projectRoot, saveCaptures, capture.header(), "cap");
            if (log.isDebugEnabled()) {
                log.debug("worker cam={} {} header={}", cameraId, debugLogSuffix, capture.header());
            }
            capture = maybeDownscale.apply(capture, cameraId);
            return new PipelineState(capture, null, null, YamlScalars.nanosToMs(System.nanoTime() - t0), 0L, 0L);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new PipelineException(e);
        }
    }

    static boolean hasUsableCaptureHeader(BinaryProtocol.Message capture) {
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
}
