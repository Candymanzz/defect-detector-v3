package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.stream.StreamException;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/** Worker capture + header validation for live preview tickers. */
final class LivePreviewCaptureSupport {

    private LivePreviewCaptureSupport() {
    }

    static BinaryProtocol.Message capture(
            WorkerProcessSupervisor worker,
            int cameraId,
            LivePreviewConfig cfg,
            LightTriggerClient lightClient,
            int flashLeadMs,
            Logger log
    ) throws StreamException {
        synchronized (worker) {
            try {
                final BinaryProtocol.Message[] captureHolder = new BinaryProtocol.Message[1];
                if (cfg.flashOnTick()) {
                    lightClient.runCaptureWithLighting(cameraId, -1L, "preview", flashLeadMs, () -> {
                        try {
                            captureHolder[0] = worker.command(Map.of("op", "capture", "sync", true));
                        } catch (java.io.IOException e) {
                            throw new com.example.iml.orchestrator.integration.capture.CaptureException(e);
                        }
                    });
                    BinaryProtocol.Message capture = captureHolder[0];
                    if (!hasUsableCaptureHeader(capture)) {
                        capture = worker.command(Map.of("op", "capture"));
                        if (log.isDebugEnabled()) {
                            log.debug(
                                    "live_preview cam={} fallback capture (sync response had no usable frame header)",
                                    cameraId);
                        }
                    }
                    return capture;
                }
                return worker.command(Map.of("op", "capture"));
            } catch (java.io.IOException e) {
                throw new StreamException(e);
            }
        }
    }

    static boolean hasUsableCaptureHeader(BinaryProtocol.Message capture) {
        if (capture == null || capture.header() == null) {
            return false;
        }
        Map<String, Object> header = capture.header();
        long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
        String shmName = String.valueOf(header.getOrDefault("shm_name", "")).trim();
        int width = YamlScalars.toInt(header.get("width"), 0);
        int height = YamlScalars.toInt(header.get("height"), 0);
        return frameId >= 0 && !shmName.isEmpty() && width > 0 && height > 0;
    }

    static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }
}
