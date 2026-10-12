package com.example.iml.orchestrator.integration.trigger;

import java.time.Instant;

/** Событие «запустить цикл инспекции» для одной камеры или для всей линии (broadcast). */
public record InspectionTriggerEvent(
        int cameraId,
        long sequence,
        Instant receivedAt,
        String source,
        boolean broadcast,
        long receivedNanos
) {
    public InspectionTriggerEvent(int cameraId, long sequence, Instant receivedAt, String source, boolean broadcast) {
        this(cameraId, sequence, receivedAt, source, broadcast, System.nanoTime());
    }

    public static InspectionTriggerEvent forCamera(int cameraId, String source) {
        return new InspectionTriggerEvent(cameraId, 0L, Instant.now(), source, false, System.nanoTime());
    }

    public static InspectionTriggerEvent lineBroadcast(String source) {
        return new InspectionTriggerEvent(-1, 0L, Instant.now(), source, true, System.nanoTime());
    }

    public static InspectionTriggerEvent of(int cameraId, String source) {
        return forCamera(cameraId, source);
    }
}
