package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutSink;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Детерминированный замер полного двухфазного цикла:
 * 2 фазы × 10 камер, по две bucket-группы в каждой фазе.
 */
class TenFramePipelineBenchmarkTest {

    private static final long PHASE_DELAY_MS = 700L;
    private static final long AGGREGATION_TIMEOUT_MS = 2500L;
    private static final long[] PHASE_0_DELAYS_MS = {
            1180L, 160L, 940L, 400L, 700L, 1060L, 280L, 820L, 520L, 1240L
    };
    private static final long[] PHASE_1_DELAYS_MS = {
            210L, 30L, 330L, 90L, 390L, 270L, 60L, 360L, 150L, 300L
    };

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
        executors.clear();
        for (AutoCloseable closeable : toClose) {
            closeable.close();
        }
        toClose.clear();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void benchmarkTwoPhasesTenCamerasPreservesOrderedPhaseAwareFanOut() throws Exception {
        BucketInspectionAggregator aggregator = track(new BucketInspectionAggregator(
                LogManager.getLogger(TenFramePipelineBenchmarkTest.class),
                new BucketInspectionConfig(
                        true,
                        List.of(
                                new BucketGroup(0, 0, List.of(0, 1, 2, 3, 4)),
                                new BucketGroup(0, 1, List.of(5, 6, 7, 8, 9)),
                                new BucketGroup(1, 2, List.of(0, 1, 2, 3, 4)),
                                new BucketGroup(1, 3, List.of(5, 6, 7, 8, 9))
                        ),
                        AGGREGATION_TIMEOUT_MS,
                        AGGREGATION_TIMEOUT_MS
                )
        ));

        List<BucketFanOutResult> published = new CopyOnWriteArrayList<>();
        List<String> arrivals = new CopyOnWriteArrayList<>();
        BucketFanOutSink fanOut = published::add;
        long parentCycleId = 100L;
        long phase0RawSequence = 100L;
        long phase1RawSequence = 101L;
        long startedAt = System.nanoTime();
        AtomicLong phase1StartedAtMs = new AtomicLong(-1L);
        ExecutorService pool = trackExecutor(Executors.newFixedThreadPool(20));
        CountDownLatch done = new CountDownLatch(20);

        for (int cameraId = 0; cameraId < 10; cameraId++) {
            int cam = cameraId;
            pool.submit(() -> {
                try {
                    sleepQuiet(PHASE_0_DELAYS_MS[cam]);
                    arrivals.add("0:" + cam);
                    aggregator.recordFrameResult(
                            phase0RawSequence,
                            parentCycleId,
                            0,
                            phase0RawSequence,
                            cam,
                            passDecision(0, cam),
                            fanOut
                    );
                } finally {
                    done.countDown();
                }
            });
        }

        sleepUntil(startedAt + TimeUnit.MILLISECONDS.toNanos(PHASE_DELAY_MS));
        phase1StartedAtMs.set(elapsedMs(startedAt));
        for (int cameraId = 0; cameraId < 10; cameraId++) {
            int cam = cameraId;
            pool.submit(() -> {
                try {
                    sleepQuiet(PHASE_1_DELAYS_MS[cam]);
                    arrivals.add("1:" + cam);
                    aggregator.recordFrameResult(
                            phase1RawSequence,
                            parentCycleId,
                            1,
                            phase1RawSequence,
                            cam,
                            passDecision(1, cam),
                            fanOut
                    );
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(2, TimeUnit.SECONDS), "all 20 concurrent results must complete");

        assertTrue(phase1StartedAtMs.get() >= PHASE_DELAY_MS, "phase1 must not start before 700 ms");
        assertTrue(
                arrivals.indexOf("1:1") < arrivals.indexOf("0:0"),
                "phase results must arrive interleaved rather than phase-by-phase"
        );
        assertEquals(
                List.of(0, 1, 2, 3),
                published.stream().map(BucketFanOutResult::groupId).toList()
        );
        assertEquals(
                List.of(0, 0, 1, 1),
                published.stream().map(BucketFanOutResult::phaseId).toList()
        );
        assertEquals(
                List.of(phase0RawSequence, phase0RawSequence, phase1RawSequence, phase1RawSequence),
                published.stream().map(BucketFanOutResult::rawTriggerSequence).toList()
        );
        assertEquals(
                List.of(phase0RawSequence, phase0RawSequence, phase1RawSequence, phase1RawSequence),
                published.stream().map(BucketFanOutResult::triggerSequence).toList()
        );
        assertTrue(published.stream().allMatch(result -> result.parentCycleId() == parentCycleId));
        assertTrue(published.stream().allMatch(BucketFanOutResult::overallPass));
        assertEquals(20, published.stream().mapToInt(result -> result.frameDecisions().size()).sum());
    }

    private static InspectionDecision passDecision(int phaseId, int cameraId) {
        return InspectionDecision.simple(
                cameraId,
                1000L + phaseId * 100L + cameraId,
                true,
                "ACCEPT",
                0.1,
                "ГОДЕН",
                "PASS"
        );
    }

    private static void sleepUntil(long deadlineNanos) {
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return;
            }
            sleepQuiet(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
        }
    }

    private static long elapsedMs(long t0Nanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0Nanos);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private <T extends AutoCloseable> T track(T closeable) {
        toClose.add(closeable);
        return closeable;
    }

    private ExecutorService trackExecutor(ExecutorService executor) {
        executors.add(executor);
        return executor;
    }
}
