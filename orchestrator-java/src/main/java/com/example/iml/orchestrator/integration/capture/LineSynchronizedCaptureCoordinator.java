package com.example.iml.orchestrator.integration.capture;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Барьер на линии: параллельный {@code trigger_only} (одна экспозиция),
 * затем последовательный {@code wait_frame} по camera_id (не забивать GigE-свитч).
 */
public final class LineSynchronizedCaptureCoordinator implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(LineSynchronizedCaptureCoordinator.class);
    private static final long FIRE_DONE_WAIT_MS = 30_000L;
    private static final int WAIT_FRAME_MAX_ATTEMPTS = 3;
    private static final long WAIT_FRAME_RETRY_MS = 100L;

    private final int expectedParties;
    private final long barrierWaitMs;
    private final long postTriggerSettleMs;
    private final long interWaitFrameMs;
    private final ExecutorService lineCaptureExecutor;
    private final ConcurrentHashMap<Long, Round> rounds = new ConcurrentHashMap<>();

    public LineSynchronizedCaptureCoordinator(Collection<Integer> cameraIds, long barrierWaitMs) {
        this(cameraIds, barrierWaitMs, 50L, 60L);
    }

    public LineSynchronizedCaptureCoordinator(
            Collection<Integer> cameraIds,
            long barrierWaitMs,
            long postTriggerSettleMs,
            long interWaitFrameMs
    ) {
        this.expectedParties = Math.max(1, cameraIds.size());
        this.barrierWaitMs = Math.max(100L, barrierWaitMs);
        this.postTriggerSettleMs = Math.max(0L, postTriggerSettleMs);
        this.interWaitFrameMs = Math.max(0L, interWaitFrameMs);
        this.lineCaptureExecutor = Executors.newFixedThreadPool(
                Math.max(1, this.expectedParties),
                r -> {
                    Thread t = new Thread(r, "line-capture");
                    t.setDaemon(true);
                    return t;
                }
        );
        LOG.info(
                "line synchronized capture enabled expected_cameras={} barrier_wait_ms={} post_trigger_settle_ms={} inter_wait_frame_ms={}",
                this.expectedParties,
                this.barrierWaitMs,
                this.postTriggerSettleMs,
                this.interWaitFrameMs
        );
    }

    public boolean isEnabled() {
        return expectedParties > 1;
    }

    public Map<Integer, BinaryProtocol.Message> captureLineBatch(
            long triggerSequence,
            Map<Integer, WorkerProcessSupervisor> workersByCamera,
            boolean lenient
    ) throws Exception {
        if (workersByCamera == null || workersByCamera.isEmpty()) {
            return Map.of();
        }
        if (!isEnabled() || triggerSequence <= 0L) {
            Map<Integer, BinaryProtocol.Message> solo = new LinkedHashMap<>();
            for (Map.Entry<Integer, WorkerProcessSupervisor> entry : workersByCamera.entrySet()) {
                solo.put(entry.getKey(), entry.getValue().command(Map.of("op", "capture", "sync", true)));
            }
            return solo;
        }
        Round round = new Round();
        List<Integer> sorted = workersByCamera.keySet().stream().sorted().toList();
        for (Integer cameraId : sorted) {
            WorkerProcessSupervisor worker = workersByCamera.get(cameraId);
            if (worker != null) {
                round.participants.put(cameraId, worker);
            }
        }
        fireLineCapture(round, lenient);
        Map<Integer, BinaryProtocol.Message> captured = new LinkedHashMap<>();
        for (Integer cameraId : sorted) {
            BinaryProtocol.Message msg = round.results.get(cameraId);
            if (!isUsableCapture(msg)) {
                if (lenient) {
                    continue;
                }
                throw new IllegalStateException(
                        "line batch cam=" + cameraId + " unusable: " + describeCapture(msg)
                );
            }
            captured.put(cameraId, msg);
        }
        if (captured.isEmpty()) {
            throw new IllegalStateException("line batch seq=" + triggerSequence + " produced no usable frames");
        }
        LOG.info(
                "line capture batch complete seq={} cameras={}/{} lenient={}",
                triggerSequence,
                captured.size(),
                sorted.size(),
                lenient
        );
        return captured;
    }

    public BinaryProtocol.Message captureForLine(
            long triggerSequence,
            int cameraId,
            WorkerProcessSupervisor worker
    ) throws Exception {
        if (!isEnabled() || triggerSequence <= 0L) {
            return worker.command(Map.of("op", "capture", "sync", true));
        }
        Round round = rounds.computeIfAbsent(triggerSequence, ignored -> new Round());
        round.arrive(cameraId, worker);
        awaitBarrier(round);
        fireIfLeader(round, triggerSequence);
        if (!round.fireDone.await(FIRE_DONE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            round.releaseParticipant();
            throw new IllegalStateException("line capture timed out waiting for fire cam=" + cameraId);
        }
        if (round.failure != null) {
            round.releaseParticipant();
            throw round.failure;
        }
        BinaryProtocol.Message capture = round.results.get(cameraId);
        round.releaseParticipant();
        if (!isUsableCapture(capture)) {
            throw new IllegalStateException(
                    "line capture unusable cam=" + cameraId + " seq=" + triggerSequence + ": " + describeCapture(capture)
            );
        }
        return capture;
    }

    private void awaitBarrier(Round round) throws InterruptedException {
        synchronized (round.awaitLock) {
            while (round.participants.size() < expectedParties) {
                long remaining = round.deadlineMs - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                round.awaitLock.wait(Math.min(25L, remaining));
            }
        }
    }

    private void fireIfLeader(Round round, long triggerSequence) {
        if (!round.fired.compareAndSet(false, true)) {
            return;
        }
        int arrived = round.participants.size();
        if (arrived < expectedParties) {
            LOG.warn(
                    "line capture partial barrier seq={} arrived={}/{} — firing for participants present",
                    triggerSequence,
                    arrived,
                    expectedParties
            );
        }
        try {
            fireLineCapture(round, false);
        } catch (Exception e) {
            round.failure = e;
        } finally {
            round.fireDone.countDown();
        }
    }

    @Override
    public void close() {
        lineCaptureExecutor.shutdownNow();
        rounds.clear();
    }

    private void fireLineCapture(Round round, boolean lenient) throws Exception {
        long t0 = System.nanoTime();
        List<Map.Entry<Integer, WorkerProcessSupervisor>> entries = new ArrayList<>(round.participants.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        List<Callable<Void>> triggerTasks = new ArrayList<>(entries.size());
        for (Map.Entry<Integer, WorkerProcessSupervisor> entry : entries) {
            WorkerProcessSupervisor worker = entry.getValue();
            triggerTasks.add(() -> {
                worker.command(Map.of("op", "capture", "trigger_only", true));
                return null;
            });
        }
        invokeAll(triggerTasks);

        if (postTriggerSettleMs > 0) {
            Thread.sleep(postTriggerSettleMs);
        }

        long tTriggerDone = System.nanoTime();
        List<Callable<Map.Entry<Integer, BinaryProtocol.Message>>> waitTasks = new ArrayList<>(entries.size());
        for (Map.Entry<Integer, WorkerProcessSupervisor> entry : entries) {
            int camId = entry.getKey();
            WorkerProcessSupervisor worker = entry.getValue();
            waitTasks.add(() -> Map.entry(camId, waitFrameWithRetry(worker, camId)));
        }
        List<Future<Map.Entry<Integer, BinaryProtocol.Message>>> waitFutures = lineCaptureExecutor.invokeAll(waitTasks);
        int okCount = 0;
        for (Future<Map.Entry<Integer, BinaryProtocol.Message>> future : waitFutures) {
            Map.Entry<Integer, BinaryProtocol.Message> result = future.get();
            int camId = result.getKey();
            BinaryProtocol.Message msg = result.getValue();
            if (!isUsableCapture(msg)) {
                if (lenient) {
                    LOG.warn("line capture cam={} skipped (lenient): {}", camId, describeCapture(msg));
                    continue;
                }
                throw new IllegalStateException("cam=" + camId + " wait_frame failed: " + describeCapture(msg));
            }
            round.results.put(camId, msg);
            okCount++;
        }

        LOG.info(
                "line capture complete cameras={}/{} trigger_phase_ms={} wait_frame_parallel_ms={} lenient={}",
                okCount,
                entries.size(),
                (tTriggerDone - t0) / 1_000_000L,
                (System.nanoTime() - tTriggerDone) / 1_000_000L,
                lenient
        );
        if (!lenient && okCount < entries.size()) {
            throw new IllegalStateException("line capture incomplete: " + okCount + "/" + entries.size());
        }
    }

    private BinaryProtocol.Message waitFrameWithRetry(WorkerProcessSupervisor worker, int cameraId) throws Exception {
        BinaryProtocol.Message last = null;
        for (int attempt = 1; attempt <= WAIT_FRAME_MAX_ATTEMPTS; attempt++) {
            last = worker.command(Map.of("op", "capture", "wait_frame", true));
            if (isUsableCapture(last)) {
                return last;
            }
            LOG.warn(
                    "line capture cam={} wait_frame attempt {}/{} unusable: {}",
                    cameraId,
                    attempt,
                    WAIT_FRAME_MAX_ATTEMPTS,
                    describeCapture(last)
            );
            if (attempt < WAIT_FRAME_MAX_ATTEMPTS) {
                Thread.sleep(WAIT_FRAME_RETRY_MS);
            }
        }
        return last;
    }

    private static boolean isUsableCapture(BinaryProtocol.Message capture) {
        if (capture == null || capture.type() == BinaryProtocol.MSG_ERROR || capture.header() == null) {
            return false;
        }
        Map<String, Object> header = capture.header();
        String shmName = String.valueOf(header.getOrDefault("shm_name", "")).trim();
        int width = YamlScalars.toInt(header.get("width"), 0);
        int height = YamlScalars.toInt(header.get("height"), 0);
        long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
        return !shmName.isEmpty() && width > 0 && height > 0 && frameId >= 0L;
    }

    private static String describeCapture(BinaryProtocol.Message capture) {
        if (capture == null) {
            return "null";
        }
        if (capture.header() == null) {
            return "type=" + capture.type() + " header=null";
        }
        return String.valueOf(capture.header());
    }

    private void invokeAll(List<Callable<Void>> tasks) throws Exception {
        List<Future<Void>> futures = lineCaptureExecutor.invokeAll(tasks);
        for (Future<Void> future : futures) {
            future.get();
        }
    }

    private final class Round {
        final ConcurrentHashMap<Integer, WorkerProcessSupervisor> participants = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Integer, BinaryProtocol.Message> results = new ConcurrentHashMap<>();
        final AtomicBoolean fired = new AtomicBoolean(false);
        final CountDownLatch fireDone = new CountDownLatch(1);
        final AtomicInteger activeParticipants = new AtomicInteger(0);
        final Object awaitLock = new Object();
        volatile long deadlineMs = System.currentTimeMillis() + barrierWaitMs;
        volatile Exception failure;

        void arrive(int cameraId, WorkerProcessSupervisor worker) {
            participants.put(cameraId, worker);
            activeParticipants.incrementAndGet();
            synchronized (awaitLock) {
                deadlineMs = System.currentTimeMillis() + barrierWaitMs;
                awaitLock.notifyAll();
            }
        }

        void releaseParticipant() {
            if (activeParticipants.decrementAndGet() == 0) {
                rounds.entrySet().removeIf(e -> e.getValue() == this);
            }
        }
    }
}
