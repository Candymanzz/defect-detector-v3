package com.example.iml.orchestrator.integration.preview;

import java.util.Map;

/** Один кадр в {@code server.preview_batch}. */
public record PreviewWsFrame(
        int cameraId,
        String productType,
        String detectorId,
        Map<String, Object> captureHeader,
        String httpPath
) {
}
