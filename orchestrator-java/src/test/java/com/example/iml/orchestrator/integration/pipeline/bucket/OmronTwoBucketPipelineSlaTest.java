package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutSink;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SLA: два независимых ведра Omron (reject_line_1 / reject_line_2) должны получить вердикт
 * не позже {@link #OMRON_TWO_BUCKET_SLA_MS} после старта цикла.
 */
class OmronTwoBucketPipelineSlaTest {

    /** Дедлайн для ПЛК Omron: 4 с на оба ведра (параллельно, не суммарно). */
    static final long OMRON_TWO_BUCKET_SLA_MS = 4000L;

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
    void aggregatorPublishesTwoOmronBucketsWithinFourSeconds() throws Exception {
        BucketInspectionAggregator aggregator = track(new BucketInspectionAggregator(
                LogManager.getLogger(OmronTwoBucketPipelineSlaTest.class),
                omronTwoBucketConfig(OMRON_TWO_BUCKET_SLA_MS)
        ));
        List<BucketFanOutResult> published = new CopyOnWriteArrayList<>();
        BucketFanOutSink fanOut = published::add;

        long triggerSequence = 100L;
        long t0 = System.nanoTime();
        ExecutorService delivery = trackExecutor(Executors.newFixedThreadPool(10));
        CountDownLatch started = new CountDownLatch(10);

        for (int cameraId = 0; cameraId < 10; cameraId++) {
            int delayMs = 200 + (cameraId % 5) * 450;
            int cam = cameraId;
            delivery.submit(() -> {
                started.countDown();
                sleepQuiet(delayMs);
                aggregator.recordFrameResult(triggerSequence, cam, passDecision(cam), fanOut);
            });
        }

        assertTrue(started.await(1, TimeUnit.SECONDS), "all delivery tasks must start");
        awaitTwoBuckets(published, OMRON_TWO_BUCKET_SLA_MS);

        long elapsedMs = nanosToMs(System.nanoTime() - t0);
        assertTrue(
                elapsedMs <= OMRON_TWO_BUCKET_SLA_MS,
                () -> "both Omron buckets must publish within " + OMRON_TWO_BUCKET_SLA_MS
                        + " ms, took " + elapsedMs + " ms"
        );
        assertEquals(Set.of(0, 1), groupIds(published));
        assertEquals(triggerSequence, published.get(0).triggerSequence());
        assertEquals(triggerSequence, published.get(1).triggerSequence());
    }

    @Test
    void bothBucketsMeetSlaWhenSlowestFrameArrivesJustBeforeDeadline() throws Exception {
        BucketInspectionAggregator aggregator = track(new BucketInspectionAggregator(
                LogManager.getLogger(OmronTwoBucketPipelineSlaTest.class),
                omronTwoBucketConfig(OMRON_TWO_BUCKET_SLA_MS)
        ));
        List<BucketFanOutResult> published = new CopyOnWriteArrayList<>();
        BucketFanOutSink fanOut = published::add;

        long triggerSequence = 150L;
        long t0 = System.nanoTime();
        ExecutorService delivery = trackExecutor(Executors.newFixedThreadPool(10));
        CountDownLatch started = new CountDownLatch(10);

        for (int cameraId = 0; cameraId < 10; cameraId++) {
            int delayMs = cameraId == 9 ? 3600 : 150 + (cameraId % 5) * 200;
            int cam = cameraId;
            delivery.submit(() -> {
                started.countDown();
                sleepQuiet(delayMs);
                aggregator.recordFrameResult(triggerSequence, cam, passDecision(cam), fanOut);
            });
        }

        assertTrue(started.await(1, TimeUnit.SECONDS));
        awaitTwoBuckets(published, OMRON_TWO_BUCKET_SLA_MS + 200L);

        long elapsedMs = nanosToMs(System.nanoTime() - t0);
        assertTrue(
                elapsedMs <= OMRON_TWO_BUCKET_SLA_MS,
                () -> "slowest frame must still complete both buckets within SLA, took " + elapsedMs + " ms"
        );
        assertEquals(Set.of(0, 1), groupIds(published));
    }

    @Test
    void bucketTimeoutStillDeliversBothOmronBucketsWithinFourSeconds() throws Exception {
        BucketInspectionAggregator aggregator = track(new BucketInspectionAggregator(
                LogManager.getLogger(OmronTwoBucketPipelineSlaTest.class),
                omronTwoBucketConfig(OMRON_TWO_BUCKET_SLA_MS)
        ));
        List<BucketFanOutResult> published = new CopyOnWriteArrayList<>();
        BucketFanOutSink fanOut = published::add;

        long triggerSequence = 300L;
        long t0 = System.nanoTime();

        for (int cameraId = 0; cameraId < 8; cameraId++) {
            aggregator.recordFrameResult(triggerSequence, cameraId, passDecision(cameraId), fanOut);
        }

        awaitTwoBuckets(published, OMRON_TWO_BUCKET_SLA_MS + 200L);

        long elapsedMs = nanosToMs(System.nanoTime() - t0);
        assertTrue(
                elapsedMs <= OMRON_TWO_BUCKET_SLA_MS + 100L,
                () -> "timeout path must publish both buckets near SLA, took " + elapsedMs + " ms"
        );
        assertEquals(2, published.size());
        assertTrue(
                published.stream().anyMatch(result -> !result.overallPass()),
                "missing frames must produce reject verdict for timed-out bucket"
        );
    }

    private static BucketInspectionConfig omronTwoBucketConfig(long timeoutMs) {
        return new BucketInspectionConfig(
                true,
                List.of(
                        new BucketGroup(0, List.of(0, 1, 2, 3, 4)),
                        new BucketGroup(1, List.of(5, 6, 7, 8, 9))
                ),
                timeoutMs,
                timeoutMs
        );
    }

    private static void awaitTwoBuckets(List<BucketFanOutResult> published, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (published.size() >= 2) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError(
                "expected 2 bucket publishes, got " + published.size() + " within " + timeoutMs + " ms"
        );
    }

    private static Set<Integer> groupIds(List<BucketFanOutResult> published) {
        return published.stream().map(BucketFanOutResult::groupId).collect(java.util.stream.Collectors.toSet());
    }

    private static InspectionDecision passDecision(int cameraId) {
        return new InspectionDecision(cameraId, 1000L + cameraId, true, "ACCEPT", 0.1, "ГОДЕН", "PASS");
    }

    private static long nanosToMs(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
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
