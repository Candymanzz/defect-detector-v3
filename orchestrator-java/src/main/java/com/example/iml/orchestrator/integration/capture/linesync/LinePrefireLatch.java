package com.example.iml.orchestrator.integration.capture.linesync;

import com.example.iml.orchestrator.integration.capture.CaptureException;


import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Immediate DI3 trigger_only (prefire) + async wait_frame latch into pinned SHM.
 */
public final class LinePrefireLatch {

    private final Logger log;
    private final LineRoundRegistry registry;
    private final LineTriggerPhase triggerPhase;
    private final WaitFrameCollector waitFrames;
    private final ExecutorService lineCaptureExecutor;
    private final Object triggerOnlyLock;
    private final Object lineCaptureSerialLock;
    private final boolean hardwareLineTrigger;
    private final boolean immediatePrefire;
    private final boolean enabled;

    public LinePrefireLatch(
            Logger log,
            LineRoundRegistry registry,
            LineTriggerPhase triggerPhase,
            WaitFrameCollector waitFrames,
            ExecutorService lineCaptureExecutor,
            Object triggerOnlyLock,
            Object lineCaptureSerialLock,
            boolean hardwareLineTrigger,
            boolean immediatePrefire,
            boolean enabled
    ) {
        this.log = log;
        this.registry = registry;
        this.triggerPhase = triggerPhase;
        this.waitFrames = waitFrames;
        this.lineCaptureExecutor = lineCaptureExecutor;
        this.triggerOnlyLock = triggerOnlyLock;
        this.lineCaptureSerialLock = lineCaptureSerialLock;
        this.hardwareLineTrigger = hardwareLineTrigger;
        this.immediatePrefire = immediatePrefire;
        this.enabled = enabled;
    }

    public void prefireLineTrigger(
            long triggerSequence,
            long triggerReceivedEpochMs,
            Collection<Integer> cameraIds,
            Map<Integer, WorkerProcessSupervisor> lineWorkers
    ) {
        if (hardwareLineTrigger || !immediatePrefire || !enabled || triggerSequence <= 0L || lineWorkers.isEmpty()) {
            return;
        }
        Map<Integer, WorkerProcessSupervisor> activeWorkers = filterWorkers(cameraIds, lineWorkers);
        if (activeWorkers.isEmpty()) {
            return;
        }
        LineCaptureRound round = registry.getOrCreate(triggerSequence);
        if (!round.triggerPrefired.compareAndSet(false, true)) {
            return;
        }
        registry.pruneOldRounds(triggerSequence);
        long triggerEpochMs = triggerReceivedEpochMs > 0L ? triggerReceivedEpochMs : System.currentTimeMillis();
        round.triggerEpochMs = triggerEpochMs;
        long t0 = System.nanoTime();
        try {
            synchronized (triggerOnlyLock) {
                triggerPhase.dispatchTriggerOnly(round, activeWorkers);
            }
            long triggerMs = (System.nanoTime() - t0) / 1_000_000L;
            long sinceUdpMs = Math.max(0L, System.currentTimeMillis() - triggerEpochMs);
            log.info(
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
            log.warn("line prefire trigger_only failed seq={}: {}", triggerSequence, e.getMessage());
            return;
        }
        Map<Integer, WorkerProcessSupervisor> latchWorkers = activeWorkers;
        lineCaptureExecutor.submit(() -> latchRoundAsync(round, triggerSequence, triggerEpochMs, latchWorkers));
    }

    private Map<Integer, WorkerProcessSupervisor> filterWorkers(
            Collection<Integer> cameraIds,
            Map<Integer, WorkerProcessSupervisor> lineWorkers
    ) {
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
            LineCaptureRound round,
            long triggerSequence,
            long triggerEpochMs,
            Map<Integer, WorkerProcessSupervisor> workers
    ) {
        synchronized (lineCaptureSerialLock) {
            long t0 = System.nanoTime();
            try {
                long waitMs = latchAllFramesAfterTrigger(round, workers, triggerSequence);
                long sinceUdpMs = Math.max(0L, System.currentTimeMillis() - triggerEpochMs);
                log.info(
                        "sync_diag channel=inspect event=line_prefire_latch trigger_sequence={} cameras={} latch_wait_ms={} since_udp_ms={}",
                        triggerSequence,
                        workers.size(),
                        waitMs,
                        sinceUdpMs
                );
            } catch (Exception e) {
                round.failure = e;
                log.warn("line prefire latch failed seq={}: {}", triggerSequence, e.getMessage());
            } finally {
                round.framesReady.countDown();
            }
            long latchTotalMs = (System.nanoTime() - t0) / 1_000_000L;
            if (latchTotalMs > 500L) {
                log.debug("line latch executor held lock ms={} seq={}", latchTotalMs, triggerSequence);
            }
        }
    }

    private long latchAllFramesAfterTrigger(
            LineCaptureRound round,
            Map<Integer, WorkerProcessSupervisor> workers,
            long triggerSequence
    ) throws CaptureException {
        if (!round.framesLatched.compareAndSet(false, true)) {
            return 0L;
        }
        long t0 = System.nanoTime();
        List<Map.Entry<Integer, WorkerProcessSupervisor>> entries = new ArrayList<>(workers.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Integer, WorkerProcessSupervisor> entry : entries) {
            round.participants.putIfAbsent(entry.getKey(), entry.getValue());
        }
        int okCount = waitFrames.collectWaitFrames(round, entries, false);
        long waitMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info(
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
}
