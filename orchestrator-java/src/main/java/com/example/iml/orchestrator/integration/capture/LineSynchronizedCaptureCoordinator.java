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
 * затем {@code wait_frame} (параллельно или последовательно).
 * При {@code immediate_prefire} команда trigger_only уходит сразу по UDP line-broadcast.
 * При {@code hardware_line_trigger} DI3 идёт на Line0 камер — Java только {@code wait_frame}, без {@code trigger_only}.
 */
public final class LineSynchronizedCaptureCoordinator implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(LineSynchronizedCaptureCoordinator.class);
    private static final long FIRE_DONE_WAIT_MS = 30_000L;
    private static final int WAIT_FRAME_MAX_ATTEMPTS = 3;
    private static final long WAIT_FRAME_RETRY_MS = 50L;
    private static final long BARRIER_POLL_MS = 2L;

    private final int expectedParties;
    private final long barrierWaitMs;
    private final long postTriggerSettleMs;
    private final long interWaitFrameMs;
    private final boolean parallelWaitFrame;
    private final int transferWaitWaves;
    private final long configuredTransferWaveGapMs;
    private final boolean immediatePrefire;
    private final boolean hardwareLineTrigger;
    private final ExecutorService lineCaptureExecutor;
    private final LineFramePinService framePinService = new LineFramePinService();
    private final ConcurrentHashMap<Long, Round> rounds = new ConcurrentHashMap<>();
    private final Object lineCaptureSerialLock = new Object();
    private final Object triggerOnlyLock = new Object();
    private volatile Map<Integer, WorkerProcessSupervisor> lineWorkers = Map.of();
    private static final long FRAMES_READY_WAIT_MS = 30_000L;

    public LineSynchronizedCaptureCoordinator(Collection<Integer> cameraIds, long barrierWaitMs) {
        this(cameraIds, barrierWaitMs, 0L, 0L, true, false);
    }

    public LineSynchronizedCaptureCoordinator(
            Collection<Integer> cameraIds,
            long barrierWaitMs,
            long postTriggerSettleMs,
            long interWaitFrameMs
    ) {
        this(cameraIds, barrierWaitMs, postTriggerSettleMs, interWaitFrameMs, true, false);
    }

    public LineSynchronizedCaptureCoordinator(
            Collection<Integer> cameraIds,
            long barrierWaitMs,
            long postTriggerSettleMs,
            long interWaitFrameMs,
            boolean parallelWaitFrame
    ) {
        this(cameraIds, barrierWaitMs, postTriggerSettleMs, interWaitFrameMs, parallelWaitFrame, false);
    }

    public LineSynchronizedCaptureCoordinator(
            Collection<Integer> cameraIds,
            long barrierWaitMs,
            long postTriggerSettleMs,
            long interWaitFrameMs,
            boolean parallelWaitFrame,
            boolean immediatePrefire
    ) {
        this(cameraIds, barrierWaitMs, postTriggerSettleMs, interWaitFrameMs, parallelWaitFrame, immediatePrefire, false, 1);
    }

    public LineSynchronizedCaptureCoordinator(
            Collection<Integer> cameraIds,
            long barrierWaitMs,
            long postTriggerSettleMs,
            long interWaitFrameMs,
            boolean parallelWaitFrame,
            boolean immediatePrefire,
            boolean hardwareLineTrigger
    ) {
        this(
                cameraIds,
                barrierWaitMs,
                postTriggerSettleMs,
                interWaitFrameMs,
                parallelWaitFrame,
                immediatePrefire,
                hardwareLineTrigger,
                1
        );
    }

    public LineSynchronizedCaptureCoordinator(
            Collection<Integer> cameraIds,
            long barrierWaitMs,
            long postTriggerSettleMs,
            long interWaitFrameMs,
            boolean parallelWaitFrame,
            boolean immediatePrefire,
            boolean hardwareLineTrigger,
            int transferWaitWaves
    ) {
        this(
                cameraIds,
                barrierWaitMs,
                postTriggerSettleMs,
                interWaitFrameMs,
                parallelWaitFrame,
                immediatePrefire,
                hardwareLineTrigger,
                transferWaitWaves,
                -1L
        );
    }

    public LineSynchronizedCaptureCoordinator(
            Collection<Integer> cameraIds,
            long barrierWaitMs,
            long postTriggerSettleMs,
            long interWaitFrameMs,
            boolean parallelWaitFrame,
            boolean immediatePrefire,
            boolean hardwareLineTrigger,
            int transferWaitWaves,
            long transferWaveGapMs
    ) {
        this.expectedParties = Math.max(1, cameraIds.size());
        this.barrierWaitMs = hardwareLineTrigger
                ? Math.max(0L, barrierWaitMs)
                : Math.max(50L, barrierWaitMs);
        this.postTriggerSettleMs = Math.max(0L, postTriggerSettleMs);
        this.interWaitFrameMs = Math.max(0L, interWaitFrameMs);
        this.parallelWaitFrame = parallelWaitFrame;
        this.transferWaitWaves = Math.max(1, transferWaitWaves);
        this.configuredTransferWaveGapMs = transferWaveGapMs;
        this.immediatePrefire = immediatePrefire;
        this.hardwareLineTrigger = hardwareLineTrigger;
        this.lineCaptureExecutor = Executors.newFixedThreadPool(
                Math.max(1, this.expectedParties),
                r -> {
                    Thread t = new Thread(r, "line-capture");
                    t.setDaemon(true);
                    t.setPriority(Thread.MAX_PRIORITY);
                    return t;
                }
        );
        LOG.info(
                "line synchronized capture enabled expected_cameras={} barrier_wait_ms={} post_trigger_settle_ms={} inter_wait_frame_ms={} parallel_wait_frame={} transfer_wait_waves={} transfer_wave_gap_ms={} immediate_prefire={} hardware_line_trigger={}",
                this.expectedParties,
                this.barrierWaitMs,
                this.postTriggerSettleMs,
                this.interWaitFrameMs,
                this.parallelWaitFrame,
                this.transferWaitWaves,
                effectiveTransferWaveGapMs(),
                this.immediatePrefire,
                this.hardwareLineTrigger
        );
        if (hardwareLineTrigger) {
            LOG.info(
                    "line capture: hardware_line_trigger — экспозиция по DI3→Line0, Java только wait_frame (capture_trigger_mode=line0)"
            );
        } else if (immediatePrefire) {
            LOG.info(
                    "line capture: DI3 trigger_only immediately (exposure at DI3+IPC ~30ms); latch+pin async (transfer_wait_waves={})",
                    transferWaitWaves
            );
        }
    }

    public void bindWorkers(Map<Integer, WorkerProcessSupervisor> workersByCamera) {
        if (workersByCamera == null || workersByCamera.isEmpty()) {
            this.lineWorkers = Map.of();
            return;
        }
        this.lineWorkers = Map.copyOf(workersByCamera);
    }

    /**
     * DI3: немедленный {@code trigger_only} (экспозиция), затем асинхронный {@code wait_frame}+pin.
     * Инспекция не блокирует следующий триггер — кадр копируется в отдельный SHM ({@link LineFramePinService}).
     */
    public void prefireLineTrigger(long triggerSequence, long triggerReceivedEpochMs) {
        prefireLineTrigger(triggerSequence, triggerReceivedEpochMs, null);
    }

    public void prefireLineTrigger(
            long triggerSequence,
            long triggerReceivedEpochMs,
            Collection<Integer> cameraIds
    ) {
        if (hardwareLineTrigger || !immediatePrefire || !isEnabled() || triggerSequence <= 0L || lineWorkers.isEmpty()) {
            return;
        }
        Map<Integer, WorkerProcessSupervisor> activeWorkers = filterWorkers(cameraIds);
        if (activeWorkers.isEmpty()) {
            return;
        }
        Round round = rounds.computeIfAbsent(triggerSequence, ignored -> new Round());
        if (!round.triggerPrefired.compareAndSet(false, true)) {
            return;
        }
        pruneOldRounds(triggerSequence);
        long triggerEpochMs = triggerReceivedEpochMs > 0L ? triggerReceivedEpochMs : System.currentTimeMillis();
        round.triggerEpochMs = triggerEpochMs;
        long t0 = System.nanoTime();
        try {
            synchronized (triggerOnlyLock) {
                dispatchTriggerOnly(round, activeWorkers);
            }
            long triggerMs = (System.nanoTime() - t0) / 1_000_000L;
            long sinceUdpMs = Math.max(0L, System.currentTimeMillis() - triggerEpochMs);
            LOG.info(
                    "sync_diag channel=inspect event=line_prefire trigger_sequence={} cameras={} trigger_only_ms={} latch_wait_ms=async since_udp_ms={} hardware={}",
                    triggerSequence,
                    activeWorkers.size(),
                    triggerMs,
                    sinceUdpMs,
                    hardwareLineTrigger
            );
        } catch (Exception e) {
            round.triggerPrefired.set(false);
            round.failure = e;
            round.framesReady.countDown();
            LOG.warn("line prefire trigger_only failed seq={}: {}", triggerSequence, e.getMessage());
            return;
        }
        Map<Integer, WorkerProcessSupervisor> latchWorkers = activeWorkers;
        lineCaptureExecutor.submit(() -> latchRoundAsync(round, triggerSequence, triggerEpochMs, latchWorkers));
    }

    private Map<Integer, WorkerProcessSupervisor> filterWorkers(Collection<Integer> cameraIds) {
        if (cameraIds == null || cameraIds.isEmpty()) {
            return lineWorkers;
        }
        Map<Integer, WorkerProcessSupervisor> filtered = new LinkedHashMap<>();
        for (Integer cameraId : cameraIds) {
            if (cameraId == null) {
                continue;
            }
            WorkerProcessSupervisor worker = lineWorkers.get(cameraId);
            if (worker != null) {
                filtered.put(cameraId, worker);
            }
        }
        return filtered;
    }

    private void latchRoundAsync(
            Round round,
            long triggerSequence,
            long triggerEpochMs,
            Map<Integer, WorkerProcessSupervisor> workers
    ) {
        synchronized (lineCaptureSerialLock) {
            long t0 = System.nanoTime();
            try {
                long waitMs = latchAllFramesAfterTrigger(round, workers, triggerSequence);
                long sinceUdpMs = Math.max(0L, System.currentTimeMillis() - triggerEpochMs);
                LOG.info(
                        "sync_diag channel=inspect event=line_prefire_latch trigger_sequence={} cameras={} latch_wait_ms={} since_udp_ms={}",
                        triggerSequence,
                        workers.size(),
                        waitMs,
                        sinceUdpMs
                );
            } catch (Exception e) {
                round.failure = e;
                LOG.warn("line prefire latch failed seq={}: {}", triggerSequence, e.getMessage());
            } finally {
                round.framesReady.countDown();
            }
            long latchTotalMs = (System.nanoTime() - t0) / 1_000_000L;
            if (latchTotalMs > 500L) {
                LOG.debug("line latch executor held lock ms={} seq={}", latchTotalMs, triggerSequence);
            }
        }
    }

    private long latchAllFramesAfterTrigger(
            Round round,
            Map<Integer, WorkerProcessSupervisor> workers,
            long triggerSequence
    ) throws Exception {
        if (!round.framesLatched.compareAndSet(false, true)) {
            return 0L;
        }
        long t0 = System.nanoTime();
        List<Map.Entry<Integer, WorkerProcessSupervisor>> entries = new ArrayList<>(workers.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Integer, WorkerProcessSupervisor> entry : entries) {
            round.participants.putIfAbsent(entry.getKey(), entry.getValue());
        }
        int okCount = collectWaitFrames(round, entries, false);
        long waitMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info(
                "sync_diag channel=inspect event=line_latch trigger_sequence={} cameras={}/{} wait_ms={}",
                triggerSequence,
                okCount,
                entries.size(),
                waitMs
        );
        if (okCount < entries.size()) {
            throw new IllegalStateException("line latch incomplete: " + okCount + "/" + entries.size());
        }
        return waitMs;
    }

    /** @deprecated use {@link #prefireLineTrigger} */
    public void captureLineAtTrigger(long triggerSequence, long triggerReceivedEpochMs) {
        prefireLineTrigger(triggerSequence, triggerReceivedEpochMs);
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
        Round round = rounds.get(triggerSequence);
        if (round == null) {
            round = rounds.computeIfAbsent(triggerSequence, ignored -> new Round());
        }
        if (round.triggerPrefired.get()) {
            if (!round.framesReady.await(FRAMES_READY_WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("line latch timed out cam=" + cameraId + " seq=" + triggerSequence);
            }
            if (round.failure != null) {
                throw round.failure;
            }
            BinaryProtocol.Message capture = round.results.get(cameraId);
            if (!isUsableCapture(capture)) {
                throw new IllegalStateException(
                        "line latched frame missing cam=" + cameraId + " seq=" + triggerSequence + ": "
                                + describeCapture(capture)
                );
            }
            round.consumedFrames.incrementAndGet();
            long frameId = YamlScalars.toLong(capture.header().get("frame_id"), -1L);
            LOG.debug(
                    "sync_diag channel=inspect event=line_frame_from_latch cam={} seq={} frame_id={}",
                    cameraId,
                    triggerSequence,
                    frameId
            );
            return capture;
        }

        round.arrive(cameraId, worker);

        if (hardwareLineTrigger) {
            BinaryProtocol.Message capture = waitFrameForCamera(round, cameraId, worker);
            round.releaseParticipant();
            if (!isUsableCapture(capture)) {
                throw new IllegalStateException(
                        "line wait_frame unusable cam=" + cameraId + " seq=" + triggerSequence + ": "
                                + describeCapture(capture)
                );
            }
            long frameId = YamlScalars.toLong(capture.header().get("frame_id"), -1L);
            LOG.debug(
                    "sync_diag channel=inspect event=line_frame_from_hw cam={} seq={} frame_id={}",
                    cameraId,
                    triggerSequence,
                    frameId
            );
            return capture;
        }

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
                round.awaitLock.wait(Math.min(BARRIER_POLL_MS, remaining));
            }
        }
    }

    private void fireIfLeader(Round round, long triggerSequence) {
        if (!round.fired.compareAndSet(false, true)) {
            return;
        }
        int arrived = round.participants.size();
        long barrierSpreadMs = round.firstArriveMs > 0 && round.lastArriveMs >= round.firstArriveMs
                ? round.lastArriveMs - round.firstArriveMs
                : -1L;
        if (arrived < expectedParties) {
            LOG.warn(
                    "line capture partial barrier seq={} arrived={}/{} spread_ms={} — firing for participants present",
                    triggerSequence,
                    arrived,
                    expectedParties,
                    barrierSpreadMs
            );
        } else if (barrierSpreadMs >= 0) {
            LOG.info(
                    "sync_diag channel=inspect event=line_barrier_ready trigger_sequence={} cameras={} barrier_spread_ms={}",
                    triggerSequence,
                    arrived,
                    barrierSpreadMs
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
        if (!hardwareLineTrigger && !round.triggerPrefired.get()) {
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
        }

        long tTriggerDone = System.nanoTime();
        if (round.triggerPrefired.get()) {
            LOG.info(
                    "sync_diag channel=inspect event=line_trigger_only_dispatched cameras={} trigger_phase_ms=0 prefire=true",
                    entries.size()
            );
            if (postTriggerSettleMs > 0) {
                Thread.sleep(postTriggerSettleMs);
            }
        } else {
            LOG.info(
                    "sync_diag channel=inspect event=line_trigger_only_dispatched cameras={} trigger_phase_ms={}",
                    entries.size(),
                    (tTriggerDone - t0) / 1_000_000L
            );
        }
        int okCount = collectWaitFrames(round, entries, lenient);

        LOG.info(
                "line capture complete cameras={}/{} trigger_phase_ms={} wait_frame_ms={} parallel={} lenient={}",
                okCount,
                entries.size(),
                (tTriggerDone - t0) / 1_000_000L,
                (System.nanoTime() - tTriggerDone) / 1_000_000L,
                parallelWaitFrame,
                lenient
        );
        if (!lenient && okCount < entries.size()) {
            throw new IllegalStateException("line capture incomplete: " + okCount + "/" + entries.size());
        }
    }

    private int collectWaitFrames(
            Round round,
            List<Map.Entry<Integer, WorkerProcessSupervisor>> entries,
            boolean lenient
    ) throws Exception {
        if (parallelWaitFrame) {
            if (transferWaitWaves <= 1) {
                return collectWaitFramesParallel(round, entries, lenient);
            }
            int okCount = 0;
            for (int wave = 0; wave < transferWaitWaves; wave++) {
                List<Map.Entry<Integer, WorkerProcessSupervisor>> waveEntries = new ArrayList<>();
                for (Map.Entry<Integer, WorkerProcessSupervisor> entry : entries) {
                    if (entry.getKey() % transferWaitWaves == wave) {
                        waveEntries.add(entry);
                    }
                }
                if (waveEntries.isEmpty()) {
                    continue;
                }
                LOG.info(
                        "sync_diag channel=inspect event=line_wait_wave wave={}/{} cameras={}",
                        wave + 1,
                        transferWaitWaves,
                        waveEntries.size()
                );
                okCount += collectWaitFramesParallel(round, waveEntries, lenient);
                if (wave + 1 < transferWaitWaves) {
                    long waveGapMs = Math.max(interWaitFrameMs, effectiveTransferWaveGapMs());
                    if (waveGapMs > 0L) {
                        Thread.sleep(waveGapMs);
                    }
                }
            }
            return okCount;
        }
        int okCount = 0;
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<Integer, WorkerProcessSupervisor> entry = entries.get(i);
            int camId = entry.getKey();
            WorkerProcessSupervisor worker = entry.getValue();
            BinaryProtocol.Message msg = waitFrameWithRetry(worker, camId);
            if (!isUsableCapture(msg)) {
                if (lenient) {
                    LOG.warn("line capture cam={} skipped (lenient): {}", camId, describeCapture(msg));
                    continue;
                }
                throw new IllegalStateException("cam=" + camId + " wait_frame failed: " + describeCapture(msg));
            }
            round.results.put(camId, framePinService.pinCapture(msg, camId));
            okCount++;
            if (interWaitFrameMs > 0 && i + 1 < entries.size()) {
                Thread.sleep(interWaitFrameMs);
            }
        }
        return okCount;
    }

    private void dispatchTriggerOnly(Round round, Map<Integer, WorkerProcessSupervisor> workers) throws Exception {
        List<Map.Entry<Integer, WorkerProcessSupervisor>> entries = new ArrayList<>(workers.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Integer, WorkerProcessSupervisor> entry : entries) {
            round.participants.putIfAbsent(entry.getKey(), entry.getValue());
        }
        List<Callable<Void>> triggerTasks = new ArrayList<>(entries.size());
        for (Map.Entry<Integer, WorkerProcessSupervisor> entry : entries) {
            WorkerProcessSupervisor worker = entry.getValue();
            triggerTasks.add(() -> {
                worker.command(Map.of("op", "capture", "trigger_only", true));
                return null;
            });
        }
        invokeAll(triggerTasks);
    }

    private BinaryProtocol.Message waitFrameForCamera(
            Round round,
            int cameraId,
            WorkerProcessSupervisor worker
    ) throws Exception {
        BinaryProtocol.Message existing = round.results.get(cameraId);
        if (isUsableCapture(existing)) {
            return existing;
        }
        BinaryProtocol.Message msg = waitFrameWithRetry(worker, cameraId);
        if (!isUsableCapture(msg)) {
            throw new IllegalStateException(
                    "line capture unusable cam=" + cameraId + ": " + describeCapture(msg)
            );
        }
        round.results.put(cameraId, framePinService.pinCapture(msg, cameraId));
        return msg;
    }

    private int collectWaitFramesParallel(
            Round round,
            List<Map.Entry<Integer, WorkerProcessSupervisor>> entries,
            boolean lenient
    ) throws Exception {
        List<Callable<Integer>> waitTasks = new ArrayList<>(entries.size());
        for (Map.Entry<Integer, WorkerProcessSupervisor> entry : entries) {
            int camId = entry.getKey();
            WorkerProcessSupervisor worker = entry.getValue();
            waitTasks.add(() -> {
                BinaryProtocol.Message msg = waitFrameWithRetry(worker, camId);
                if (!isUsableCapture(msg)) {
                    if (lenient) {
                        LOG.warn("line capture cam={} skipped (lenient): {}", camId, describeCapture(msg));
                        return 0;
                    }
                    throw new IllegalStateException("cam=" + camId + " wait_frame failed: " + describeCapture(msg));
                }
                round.results.put(camId, framePinService.pinCapture(msg, camId));
                return 1;
            });
        }
        List<Future<Integer>> futures = lineCaptureExecutor.invokeAll(waitTasks);
        int okCount = 0;
        for (Future<Integer> future : futures) {
            okCount += future.get();
        }
        return okCount;
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
                long retryMs = hardwareLineTrigger ? 0L : WAIT_FRAME_RETRY_MS;
                if (retryMs > 0L) {
                    Thread.sleep(retryMs);
                }
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

    private long effectiveTransferWaveGapMs() {
        if (transferWaitWaves <= 1) {
            return 0L;
        }
        if (configuredTransferWaveGapMs >= 0L) {
            return configuredTransferWaveGapMs;
        }
        return 220L;
    }

    private void pruneOldRounds(long currentSequence) {
        long cutoff = currentSequence - 32L;
        if (cutoff <= 0L) {
            return;
        }
        rounds.entrySet().removeIf(entry -> {
            if (entry.getKey() >= cutoff) {
                return false;
            }
            Round round = entry.getValue();
            if (round.consumedFrames.get() < expectedParties) {
                return false;
            }
            return true;
        });
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
        final AtomicBoolean triggerPrefired = new AtomicBoolean(false);
        final AtomicBoolean framesLatched = new AtomicBoolean(false);
        final CountDownLatch framesReady = new CountDownLatch(1);
        final AtomicInteger consumedFrames = new AtomicInteger(0);
        final CountDownLatch fireDone = new CountDownLatch(1);
        final AtomicInteger framesPending = new AtomicInteger(0);
        final AtomicInteger activeParticipants = new AtomicInteger(0);
        final Object awaitLock = new Object();
        volatile long deadlineMs = System.currentTimeMillis() + barrierWaitMs;
        volatile long firstArriveMs;
        volatile long lastArriveMs;
        volatile long prefireStartedNs;
        volatile long triggerEpochMs;
        volatile Exception failure = null;

        void arrive(int cameraId, WorkerProcessSupervisor worker) {
            participants.put(cameraId, worker);
            activeParticipants.incrementAndGet();
            long now = System.currentTimeMillis();
            if (firstArriveMs == 0L) {
                firstArriveMs = now;
            }
            lastArriveMs = now;
            synchronized (awaitLock) {
                deadlineMs = now + barrierWaitMs;
                awaitLock.notifyAll();
            }
        }

        void releaseParticipant() {
            activeParticipants.decrementAndGet();
        }
    }
}
