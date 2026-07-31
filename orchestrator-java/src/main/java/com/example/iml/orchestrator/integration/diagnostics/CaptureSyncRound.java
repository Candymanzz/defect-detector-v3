package com.example.iml.orchestrator.integration.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Mutable per-round sync diagnostics state. */
final class CaptureSyncRound {

    final long startNs;
    final Set<Integer> expectedCameraIds;
    final Map<Integer, Long> captureOkByCamera = new ConcurrentHashMap<>();
    final Map<Integer, Long> captureElapsedMsByCamera = new ConcurrentHashMap<>();
    final Map<Integer, Long> wsSentByCamera = new ConcurrentHashMap<>();
    final ConcurrentHashMap.KeySetView<Integer, Boolean> captureFailed = ConcurrentHashMap.newKeySet();

    CaptureSyncRound(long startNs, Set<Integer> expectedCameraIds) {
        this.startNs = startNs;
        this.expectedCameraIds = expectedCameraIds == null ? Set.of() : Set.copyOf(expectedCameraIds);
    }

    void captureOk(int cameraId, long frameId, long elapsedMs) {
        captureOkByCamera.put(cameraId, frameId);
        captureElapsedMsByCamera.put(cameraId, elapsedMs);
    }

    void captureFail(int cameraId) {
        captureFailed.add(cameraId);
    }

    void wsSend(int cameraId, long sinceRoundMs) {
        wsSentByCamera.put(cameraId, sinceRoundMs);
    }

    long elapsedMs() {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    long captureSpreadMs() {
        return spreadMs(captureElapsedMsByCamera);
    }

    long wsSpreadMs() {
        return spreadMs(wsSentByCamera);
    }

    private static long spreadMs(Map<Integer, Long> values) {
        if (values.size() < 2) {
            return 0L;
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long value : values.values()) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return max - min;
    }
}
