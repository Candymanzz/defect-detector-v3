package com.example.iml.orchestrator.integration.diagnostics;

import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
    private final ConcurrentHashMap<Long, Round> rounds = new ConcurrentHashMap<>();

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
        Round state = new Round(System.nanoTime(), expected);
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
        Round state = rounds.get(round);
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
        Round state = rounds.get(round);
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
        Round state = rounds.get(round);
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
        Round state = rounds.get(round);
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

    public void recordLineEvent(String event, long triggerSequence, int cameraCount, long staggerMs) {
        log.info(
                "sync_diag channel={} event={} trigger_sequence={} cameras={} stagger_ms={}",
                channel,
                event,
                triggerSequence,
                cameraCount,
                staggerMs
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
        Round state = rounds.remove(round);
        if (state == null) {
            return;
        }
        List<Integer> missing = new ArrayList<>();
        for (Integer cam : state.expectedCameraIds) {
            if (!state.captureOkByCamera.containsKey(cam) && !state.captureFailed.contains(cam)) {
                missing.add(cam);
            }
        }
        log.info(
                "sync_diag channel={} event=round_summary round={} capture_ok={}/{} ws_sent={} "
                        + "capture_spread_ms={} ws_spread_ms={} missing_cams={} failed_cams={} frames={}",
                channel,
                round,
                state.captureOkByCamera.size(),
                state.expectedCameraIds.size(),
                state.wsSentByCamera.size(),
                state.captureSpreadMs(),
                state.wsSpreadMs(),
                missing,
                List.copyOf(state.captureFailed),
                formatFrames(state.captureOkByCamera)
        );
        if (!missing.isEmpty() || state.captureFailed.size() > 0) {
            log.warn(
                    "sync_diag channel={} event=round_incomplete round={} hint=проблема на бэкенде (capture), не только фронт",
                    channel,
                    round
            );
        } else if (state.wsSpreadMs() > 50L && state.captureSpreadMs() <= 50L) {
            log.warn(
                    "sync_diag channel={} event=round_ws_desync round={} capture_spread_ms={} ws_spread_ms={} "
                            + "hint=кадры на бэкенде почти одновременно, на фронт уходят с разбросом",
                    channel,
                    round,
                    state.captureSpreadMs(),
                    state.wsSpreadMs()
            );
        }
    }

    private static String formatFrames(Map<Integer, Long> framesByCamera) {
        TreeMap<Integer, Long> sorted = new TreeMap<>(framesByCamera);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<Integer, Long> entry : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        rounds.clear();
    }

    private static final class Round {
        final long startNs;
        final Set<Integer> expectedCameraIds;
        final Map<Integer, Long> captureOkByCamera = new ConcurrentHashMap<>();
        final Map<Integer, Long> captureElapsedMsByCamera = new ConcurrentHashMap<>();
        final Map<Integer, Long> wsSentByCamera = new ConcurrentHashMap<>();
        final ConcurrentHashMap.KeySetView<Integer, Boolean> captureFailed = ConcurrentHashMap.newKeySet();

        Round(long startNs, Set<Integer> expectedCameraIds) {
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
}
