package com.example.iml.orchestrator.integration.trigger;

import java.time.Instant;

/** Событие «запустить цикл инспекции» для камеры. */
public record InspectionTriggerEvent(
        int cameraId,
        long sequence,
        Instant receivedAt,
        String source
) {
    public static InspectionTriggerEvent of(int cameraId, String source) {
        return new InspectionTriggerEvent(cameraId, 0L, Instant.now(), source);
    }
}
