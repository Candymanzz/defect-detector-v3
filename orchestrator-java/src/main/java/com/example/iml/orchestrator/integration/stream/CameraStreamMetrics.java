package com.example.iml.orchestrator.integration.stream;

import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Periodic encode/fps stats for a client camera stream. */
final class CameraStreamMetrics {

    private static final long LOG_EVERY_MS = 10_000L;

    final LongAdder frames = new LongAdder();
    final LongAdder encodeNs = new LongAdder();
    final AtomicLong lastLogAtMs = new AtomicLong(System.currentTimeMillis());

    void maybeLog(Logger log, int cameraId) {
        long now = System.currentTimeMillis();
        long prev = lastLogAtMs.get();
        if (now - prev < LOG_EVERY_MS) {
            return;
        }
        if (!lastLogAtMs.compareAndSet(prev, now)) {
            return;
        }
        long frameCount = frames.sumThenReset();
        long encodeTotalNs = encodeNs.sumThenReset();
        double sec = LOG_EVERY_MS / 1000.0;
        double fps = frameCount / sec;
        double avgEncodeMs = frameCount == 0 ? 0.0 : (encodeTotalNs / 1_000_000.0) / frameCount;
        log.info(
                "client_stream_stats camera={} fps={} avg_encode_ms={}",
                cameraId,
                String.format("%.2f", fps),
                String.format("%.2f", avgEncodeMs)
        );
    }
}
