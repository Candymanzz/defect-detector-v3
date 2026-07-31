package com.example.iml.orchestrator.integration.capture.linesync;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.LineFramePinService;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Assembles line-sync collaborators for {@code LineSynchronizedCaptureCoordinator}. */
public final class LineSynchronizedCaptureComponents {

    public final int expectedParties;
    public final long barrierWaitMs;
    public final boolean hardwareLineTrigger;
    public final ExecutorService lineCaptureExecutor;
    public final LineFramePinService framePinService;
    public final Object lineCaptureSerialLock;
    public final Object triggerOnlyLock;
    public final LineRoundRegistry registry;
    public final WaitFrameCollector waitFrames;
    public final LineTriggerPhase triggerPhase;
    public final LineBarrierLeader barrierLeader;
    public final LinePrefireLatch prefireLatch;

    private LineSynchronizedCaptureComponents(
            int expectedParties,
            long barrierWaitMs,
            boolean hardwareLineTrigger,
            ExecutorService lineCaptureExecutor,
            LineFramePinService framePinService,
            Object lineCaptureSerialLock,
            Object triggerOnlyLock,
            LineRoundRegistry registry,
            WaitFrameCollector waitFrames,
            LineTriggerPhase triggerPhase,
            LineBarrierLeader barrierLeader,
            LinePrefireLatch prefireLatch
    ) {
        this.expectedParties = expectedParties;
        this.barrierWaitMs = barrierWaitMs;
        this.hardwareLineTrigger = hardwareLineTrigger;
        this.lineCaptureExecutor = lineCaptureExecutor;
        this.framePinService = framePinService;
        this.lineCaptureSerialLock = lineCaptureSerialLock;
        this.triggerOnlyLock = triggerOnlyLock;
        this.registry = registry;
        this.waitFrames = waitFrames;
        this.triggerPhase = triggerPhase;
        this.barrierLeader = barrierLeader;
        this.prefireLatch = prefireLatch;
    }

    public static LineSynchronizedCaptureComponents create(
            Logger log,
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
        int expectedParties = Math.max(1, cameraIds.size());
        long waitMs = hardwareLineTrigger
                ? Math.max(0L, barrierWaitMs)
                : Math.max(50L, barrierWaitMs);
        long settleMs = Math.max(0L, postTriggerSettleMs);
        long interWaitMs = Math.max(0L, interWaitFrameMs);
        int waves = Math.max(1, transferWaitWaves);
        long effectiveWaveGap = effectiveTransferWaveGapMs(waves, transferWaveGapMs);
        Object lineCaptureSerialLock = new Object();
        Object triggerOnlyLock = new Object();
        LineFramePinService framePinService = new LineFramePinService();
        ExecutorService lineCaptureExecutor = Executors.newFixedThreadPool(
                Math.max(1, expectedParties),
                r -> {
                    Thread t = new Thread(r, "line-capture");
                    t.setDaemon(true);
                    t.setPriority(Thread.MAX_PRIORITY);
                    return t;
                }
        );
        LineRoundRegistry registry = new LineRoundRegistry(log, waitMs, expectedParties);
        WaitFrameCollector waitFrames = new WaitFrameCollector(
                log,
                lineCaptureExecutor,
                framePinService,
                parallelWaitFrame,
                waves,
                interWaitMs,
                hardwareLineTrigger,
                effectiveWaveGap
        );
        LineTriggerPhase triggerPhase = new LineTriggerPhase(
                log,
                lineCaptureExecutor,
                waitFrames,
                hardwareLineTrigger,
                settleMs,
                parallelWaitFrame
        );
        LineBarrierLeader barrierLeader = new LineBarrierLeader(log, expectedParties, triggerPhase);
        LinePrefireLatch prefireLatch = new LinePrefireLatch(
                log,
                registry,
                triggerPhase,
                waitFrames,
                lineCaptureExecutor,
                triggerOnlyLock,
                lineCaptureSerialLock,
                hardwareLineTrigger,
                immediatePrefire,
                expectedParties > 1
        );
        log.info(
                "line synchronized capture enabled expected_cameras={} barrier_wait_ms={} post_trigger_settle_ms={} inter_wait_frame_ms={} parallel_wait_frame={} transfer_wait_waves={} transfer_wave_gap_ms={} immediate_prefire={} hardware_line_trigger={}",
                expectedParties,
                waitMs,
                settleMs,
                interWaitMs,
                parallelWaitFrame,
                waves,
                effectiveWaveGap,
                immediatePrefire,
                hardwareLineTrigger
        );
        if (hardwareLineTrigger) {
            log.info(
                    "line capture: hardware_line_trigger — экспозиция по DI3→Line0, Java только wait_frame (capture_trigger_mode=line0)"
            );
        } else if (immediatePrefire) {
            log.info(
                    "line capture: DI3 trigger_only immediately (exposure at DI3+IPC ~30ms); latch+pin async (transfer_wait_waves={})",
                    waves
            );
        }
        return new LineSynchronizedCaptureComponents(
                expectedParties,
                waitMs,
                hardwareLineTrigger,
                lineCaptureExecutor,
                framePinService,
                lineCaptureSerialLock,
                triggerOnlyLock,
                registry,
                waitFrames,
                triggerPhase,
                barrierLeader,
                prefireLatch
        );
    }

    private static long effectiveTransferWaveGapMs(int transferWaitWaves, long configuredTransferWaveGapMs) {
        if (transferWaitWaves <= 1) {
            return 0L;
        }
        if (configuredTransferWaveGapMs >= 0L) {
            return configuredTransferWaveGapMs;
        }
        return 220L;
    }
}
