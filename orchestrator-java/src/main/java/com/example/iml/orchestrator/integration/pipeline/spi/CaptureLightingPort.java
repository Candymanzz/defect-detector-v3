package com.example.iml.orchestrator.integration.pipeline.spi;

/**
 * Подсветка вокруг capture — без привязки к concrete LightTriggerClient.
 */
public interface CaptureLightingPort {

    @FunctionalInterface
    interface CaptureStep {
        void run() throws Exception;
    }

    void runCaptureWithLighting(
            int cameraId,
            long frameId,
            String phase,
            int flashLeadMs,
            CaptureStep captureStep
    ) throws Exception;
}
