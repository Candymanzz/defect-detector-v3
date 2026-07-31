package com.example.iml.orchestrator.integration.preview;

import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class LivePreviewMetrics {
    private static final long LOG_EVERY_MS = 10_000L;

    private final ConcurrentHashMap<Integer, CameraMetrics> metricsByCamera = new ConcurrentHashMap<>();

    CameraMetrics forCamera(int cameraId) {
        return metricsByCamera.computeIfAbsent(cameraId, ignored -> new CameraMetrics());
    }

    void initialize(int cameraId) {
        forCamera(cameraId);
    }

    static final class CameraMetrics {
        final LongAdder frames = new LongAdder();
        final LongAdder droppedTicks = new LongAdder();
        final LongAdder encodeNs = new LongAdder();
        final LongAdder wsNs = new LongAdder();
        private final AtomicLong lastLogAtMs = new AtomicLong(System.currentTimeMillis());

        void maybeLog(Logger log, int cameraId) {
            long now = System.currentTimeMillis();
            long prev = lastLogAtMs.get();
            if (now - prev < LOG_EVERY_MS || !lastLogAtMs.compareAndSet(prev, now)) {
                return;
            }
            long frameCount = frames.sumThenReset();
            long dropped = droppedTicks.sumThenReset();
            long encodeTotalNs = encodeNs.sumThenReset();
            long wsTotalNs = wsNs.sumThenReset();
            double sec = LOG_EVERY_MS / 1000.0;
            double fps = frameCount / sec;
            double avgEncodeMs = frameCount == 0 ? 0.0 : (encodeTotalNs / 1_000_000.0) / frameCount;
            double avgWsMs = frameCount == 0 ? 0.0 : (wsTotalNs / 1_000_000.0) / frameCount;
            log.info(
                    "live_preview_stats camera={} fps={} dropped_ticks={} avg_encode_ms={} avg_ws_send_ms={}",
                    cameraId,
                    String.format("%.2f", fps),
                    dropped,
                    String.format("%.2f", avgEncodeMs),
                    String.format("%.2f", avgWsMs)
            );
        }
    }
}
