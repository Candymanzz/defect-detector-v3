package com.example.iml.orchestrator.integration.diagnostics;

import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Сводные логи для проверки синхронности захвата на бэкенде vs доставки на фронт.
 * Искать в логах: {@code sync_diag}
 */
public final class CaptureSyncDiagnostics implements AutoCloseable {

    private final Logger log;
    private final String channel;
    private final long summaryDelayMs;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong roundSeq = new AtomicLong(0L);
    private final ConcurrentHashMap<Long, CaptureSyncRound> rounds = new ConcurrentHashMap<>();

    public CaptureSyncDiagnostics(Logger log, String channel, long summaryDelayMs) {
        this.log = log;
        this.channel = channel;
        this.summaryDelayMs = Math.max(500L, summaryDelayMs);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync-diag-" + channel);
            t.setDaemon(true);
            return t;
        });
    }

    public long beginRound(Collection<Integer> expectedCameraIds) {
        long round = roundSeq.incrementAndGet();
        Set<Integer> expected = expectedCameraIds == null ? Set.of() : new HashSet<>(expectedCameraIds);
        CaptureSyncRound state = new CaptureSyncRound(System.nanoTime(), expected);
        rounds.put(round, state);
        log.info(
                "sync_diag channel={} event=round_start round={} expected_cameras={} camera_ids={}",
                channel,
                round,
                expected.size(),
                expected
        );
        scheduler.schedule(() -> summarize(round), summaryDelayMs, TimeUnit.MILLISECONDS);
        return round;
    }

    public void recordCaptureOk(
            long round,
            int cameraId,
            long frameId,
            long workerCaptureStartedNs,
            long workerLatencyNs,
            long orchestratorElapsedMs
    ) {
        CaptureSyncRound state = rounds.get(round);
        if (state == null) {
            return;
        }
        state.captureOk(cameraId, frameId, orchestratorElapsedMs);
        log.info(
                "sync_diag channel={} event=capture_ok round={} cam={} frame_id={} orch_ms={} worker_latency_ms={} worker_capture_started_ns={}",
                channel,
                round,
                cameraId,
                frameId,
                orchestratorElapsedMs,
                workerLatencyNs / 1_000_000L,
                workerCaptureStartedNs
        );
    }

    public void recordCaptureFail(long round, int cameraId, String reason, long orchestratorElapsedMs) {
        CaptureSyncRound state = rounds.get(round);
        if (state == null) {
            return;
        }
        state.captureFail(cameraId);
        log.warn(
                "sync_diag channel={} event=capture_fail round={} cam={} orch_ms={} reason={}",
                channel,
                round,
                cameraId,
                orchestratorElapsedMs,
                reason == null ? "unknown" : reason
        );
    }

    public void recordCaptureSkipped(long round, int cameraId, String reason) {
        CaptureSyncRound state = rounds.get(round);
        if (state == null) {
            return;
        }
        state.captureFail(cameraId);
        log.info(
                "sync_diag channel={} event=capture_skip round={} cam={} reason={}",
                channel,
                round,
                cameraId,
                reason == null ? "unknown" : reason
        );
    }

    public void recordWsSend(long round, int cameraId, long frameId) {
        CaptureSyncRound state = rounds.get(round);
        if (state == null) {
            return;
        }
        long sinceRoundMs = state.elapsedMs();
        state.wsSend(cameraId, sinceRoundMs);
        log.info(
                "sync_diag channel={} event=ws_send round={} cam={} frame_id={} since_round_ms={}",
                channel,
                round,
                cameraId,
                frameId,
                sinceRoundMs
        );
    }

    public static void logInspectCapture(
            Logger log,
            int cameraId,
            long frameId,
            long orchestratorElapsedMs,
            long workerCaptureStartedNs,
            long workerLatencyNs
    ) {
        log.info(
                "sync_diag channel=inspect event=capture_ok cam={} frame_id={} orch_ms={} worker_latency_ms={} worker_capture_started_ns={}",
                cameraId,
                frameId,
                orchestratorElapsedMs,
                workerLatencyNs / 1_000_000L,
                workerCaptureStartedNs
        );
    }

    public static void logInspectCaptureFail(Logger log, int cameraId, String reason, long orchestratorElapsedMs) {
        log.warn(
                "sync_diag channel=inspect event=capture_fail cam={} orch_ms={} reason={}",
                cameraId,
                orchestratorElapsedMs,
                reason == null ? "unknown" : reason
        );
    }

    private void summarize(long round) {
        CaptureSyncRoundSummarizer.summarize(log, channel, round, rounds.remove(round));
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        rounds.clear();
    }
}
