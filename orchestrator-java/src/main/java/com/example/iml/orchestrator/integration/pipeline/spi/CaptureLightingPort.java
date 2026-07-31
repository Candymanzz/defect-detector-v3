package com.example.iml.orchestrator.integration.pipeline.spi;

import com.example.iml.orchestrator.integration.capture.CaptureException;
import com.example.iml.orchestrator.integration.lighting.LightingException;

/**
 * Подсветка вокруг capture — без привязки к concrete LightTriggerClient.
 */
public interface CaptureLightingPort {

    @FunctionalInterface
    interface CaptureStep {
        void run() throws CaptureException;
    }

    void runCaptureWithLighting(
            int cameraId,
            long frameId,
            String phase,
            int flashLeadMs,
            CaptureStep captureStep
    ) throws LightingException;
}
