package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutSink;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Замер: 10 кадров (2 ведра Omron × 5 камер), профиль задержек как на линии.
 */
class TenFramePipelineBenchmarkTest {

    private static final long SLA_MS = 4000L;

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
    void benchmarkTenFramesTwoBuckets() throws Exception {
        BucketInspectionAggregator aggregator = track(new BucketInspectionAggregator(
                LogManager.getLogger(TenFramePipelineBenchmarkTest.class),
                new BucketInspectionConfig(
                        true,
                        List.of(
                                new BucketGroup(0, List.of(0, 1, 2, 3, 4)),
                                new BucketGroup(1, List.of(5, 6, 7, 8, 9))
                        ),
                        SLA_MS,
                        SLA_MS
                )
        ));

        Map<Integer, Long> frameDoneMs = new ConcurrentHashMap<>();
        Map<Integer, Long> bucketDoneMs = new ConcurrentHashMap<>();
        List<BucketFanOutResult> published = new CopyOnWriteArrayList<>();
        long[] t0Holder = new long[1];

        BucketFanOutSink fanOut = result -> {
            long now = elapsedMs(t0Holder[0]);
            bucketDoneMs.put(result.groupId(), now);
            published.add(result);
        };

        long triggerSequence = 1L;
        t0Holder[0] = System.nanoTime();
        long t0 = t0Holder[0];
        ExecutorService pool = trackExecutor(Executors.newFixedThreadPool(10));
        CountDownLatch done = new CountDownLatch(10);

        for (int cameraId = 0; cameraId < 10; cameraId++) {
            int cam = cameraId;
            pool.submit(() -> {
                try {
                    long frameMs = simulatePipelineMs(cam);
                    sleepQuiet(frameMs);
                    aggregator.recordFrameResult(
                            triggerSequence,
                            cam,
                            InspectionDecision.simple(cam, 1000L + cam, true, "ACCEPT", 0.1, "ГОДЕН", "PASS"),
                            fanOut
                    );
                    frameDoneMs.put(cam, elapsedMs(t0));
                } finally {
                    done.countDown();
                }
            });
        }

        assertEquals(true, done.await(SLA_MS + 500L, TimeUnit.MILLISECONDS));
        awaitBuckets(published, 2, SLA_MS + 500L);

        long wallMs = elapsedMs(t0);
        long bucket0Ms = bucketDoneMs.getOrDefault(0, -1L);
        long bucket1Ms = bucketDoneMs.getOrDefault(1, -1L);
        long slowestFrameMs = frameDoneMs.values().stream().max(Long::compare).orElse(-1L);
        int framesOk = published.stream().mapToInt(r -> r.frameDecisions().size()).sum();

        StringBuilder report = new StringBuilder();
        report.append("\n=== 10-frame pipeline benchmark ===\n");
        report.append(String.format("SLA target:        %d ms%n", SLA_MS));
        report.append(String.format("Frames completed:  %d / 10%n", frameDoneMs.size()));
        report.append(String.format("Bucket frames:     %d / 10 (in published results)%n", framesOk));
        report.append(String.format("Bucket 0 (Omron 1): %d ms  pass=%s  frames=%d/5%n",
                bucket0Ms,
                published.stream().filter(r -> r.groupId() == 0).findFirst().map(BucketFanOutResult::overallPass).orElse(false),
                published.stream().filter(r -> r.groupId() == 0).findFirst().map(r -> r.frameDecisions().size()).orElse(0)));
        report.append(String.format("Bucket 1 (Omron 2): %d ms  pass=%s  frames=%d/5%n",
                bucket1Ms,
                published.stream().filter(r -> r.groupId() == 1).findFirst().map(BucketFanOutResult::overallPass).orElse(false),
                published.stream().filter(r -> r.groupId() == 1).findFirst().map(r -> r.frameDecisions().size()).orElse(0)));
        report.append(String.format("Slowest frame:     %d ms (cam %d)%n",
                slowestFrameMs,
                frameDoneMs.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(-1)));
        report.append(String.format("Wall clock (both): %d ms  SLA %s%n",
                wallMs, wallMs <= SLA_MS ? "OK" : "FAIL"));
        report.append("Per-camera (simulated pipeline ms -> done ms):\n");
        frameDoneMs.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .forEach(e -> report.append(String.format("  cam-%d: pipeline~%4d ms  done@%4d ms  bucket=%d%n",
                        e.getKey(), simulatePipelineMs(e.getKey()), e.getValue(), e.getKey() < 5 ? 0 : 1)));
        report.append("===================================\n");

        System.out.println(report);

        assertEquals(10, frameDoneMs.size());
        assertEquals(2, published.size());
        assertEquals(10, framesOk);
    }

    /** capture + geometry + python + stagger (мс), по профилю 10 GigE камер. */
    private static long simulatePipelineMs(int cameraId) {
        int stagger = (cameraId % 5) * 120;
        int capture = 80 + (cameraId % 3) * 30;
        int geometry = 60 + (cameraId % 2) * 40;
        int python = 400 + (cameraId % 5) * 280;
        return stagger + capture + geometry + python;
    }

    private static void awaitBuckets(List<BucketFanOutResult> published, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (published.size() >= expected) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("expected " + expected + " buckets, got " + published.size());
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
