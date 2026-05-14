package com.example.iml.orchestrator.integration.pipeline;

/** Решение по кадру после geometry и python. */
public record InspectionDecision(
        int cameraId,
        long frameId,
        boolean overallPass,
        String action,
        double anomalyScore,
        String pythonStatus,
        String geometryStatus
) {
}
