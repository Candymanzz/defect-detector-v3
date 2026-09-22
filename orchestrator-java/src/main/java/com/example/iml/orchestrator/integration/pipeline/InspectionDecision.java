package com.example.iml.orchestrator.integration.pipeline;

import java.util.Map;

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
        boolean jointPass,
        /** Компактный geometry header для UI (WS/archive); может быть пустым. */
        Map<String, Object> geometry
) {
    public InspectionDecision {
        geometry = geometry == null || geometry.isEmpty() ? Map.of() : Map.copyOf(geometry);
    }

    /**
     * A failed/missing analysis response is fail-safe REJECT, but it did not
     * actually measure an anomaly. Do not expose the internal numeric fallback
     * as a real zero score to operators.
     */
    public boolean hasAnomalyScore() {
        if (!Double.isFinite(anomalyScore) || pythonStatus == null) {
            return false;
        }
        String status = pythonStatus.trim().toUpperCase(java.util.Locale.ROOT);
        return !status.isEmpty()
                && !"UNKNOWN".equals(status)
                && !"FAIL".equals(status)
                && !"ERROR".equals(status)
                && !"NO_REFERENCE".equals(status)
                && !"SKIPPED".equals(status);
    }

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
                true,
                Map.of()
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
                true,
                Map.of()
        );
    }
}
