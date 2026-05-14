package com.example.iml.orchestrator.integration.fanout;

/**
 * Событие результата инспекции для публикации роботу и HTTP-клиенту (контракт fan-out).
 */
public record FanOutEvent(
        int cameraId,
        long frameId,
        boolean overallPass,
        String action,
        double anomalyScore,
        String pythonStatus,
        String geometryStatus,
        long timestampMs
) {
}
