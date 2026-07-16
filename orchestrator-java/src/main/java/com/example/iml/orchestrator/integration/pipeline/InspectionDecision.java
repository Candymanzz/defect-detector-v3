package com.example.iml.orchestrator.integration.pipeline;

/** Решение по кадру после geometry и python. */
public record InspectionDecision(
        int cameraId,
        long frameId,
        boolean overallPass,
        String action,
        double anomalyScore,
        String pythonStatus,
        String geometryStatus,
        boolean jointCamera,
        double jointParallelismDeg,
        double jointWidthMm,
        double jointVisibility,
        boolean jointPass
) {
    public static InspectionDecision captureOnly(int cameraId, long frameId) {
        return new InspectionDecision(
                cameraId,
                frameId,
                false,
                "CAPTURE",
                0.0,
                "NO_REFERENCE",
                "SKIPPED",
                false,
                0.0,
                0.0,
                0.0,
                true
        );
    }

    /** Совместимость для тестов без метрик шва. */
    public static InspectionDecision simple(
            int cameraId,
            long frameId,
            boolean overallPass,
            String action,
            double anomalyScore,
            String pythonStatus,
            String geometryStatus
    ) {
        return new InspectionDecision(
                cameraId,
                frameId,
                overallPass,
                action,
                anomalyScore,
                pythonStatus,
                geometryStatus,
                false,
                0.0,
                0.0,
                0.0,
                true
        );
    }
}
