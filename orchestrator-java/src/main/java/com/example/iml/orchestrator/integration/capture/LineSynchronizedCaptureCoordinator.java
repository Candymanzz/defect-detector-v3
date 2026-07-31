package com.example.iml.orchestrator.integration.capture;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.linesync.LineBarrierLeader;
import com.example.iml.orchestrator.integration.capture.linesync.LineCaptureExecutionSupport;
import com.example.iml.orchestrator.integration.capture.linesync.LinePrefireLatch;
import com.example.iml.orchestrator.integration.capture.linesync.LineRoundRegistry;
import com.example.iml.orchestrator.integration.capture.linesync.LineSynchronizedCaptureComponents;
import com.example.iml.orchestrator.integration.capture.linesync.LineTriggerPhase;
import com.example.iml.orchestrator.integration.capture.linesync.WaitFrameCollector;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.IntConsumer;

/**
 * Барьер на линии: параллельный {@code trigger_only} (одна экспозиция),
 * затем {@code wait_frame} (параллельно или последовательно).
 * При {@code immediate_prefire} команда trigger_only уходит сразу по UDP line-broadcast.
 * При {@code hardware_line_trigger} DI3 идёт на Line0 камер — Java только {@code wait_frame}, без {@code trigger_only}.
 * Domain details live in {@code capture.linesync.*}.
 */
public final class LineSynchronizedCaptureCoordinator implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(LineSynchronizedCaptureCoordinator.class);

    private final int expectedParties;
    private final long barrierWaitMs;
    private final boolean hardwareLineTrigger;
    private final ExecutorService lineCaptureExecutor;
    private volatile Map<Integer, WorkerProcessSupervisor> lineWorkers = Map.of();
    private final LineRoundRegistry registry;
    private final WaitFrameCollector waitFrames;
    private final LineTriggerPhase triggerPhase;
    private final LineBarrierLeader barrierLeader;
    private final LinePrefireLatch prefireLatch;

    public LineSynchronizedCaptureCoordinator(Collection<Integer> cameraIds, long barrierWaitMs) {
        this(cameraIds, barrierWaitMs, 0L, 0L, true, false);
    }

    public LineSynchronizedCaptureCoordinator(
            Collection<Integer> cameraIds, long barrierWaitMs, long postTriggerSettleMs, long interWaitFrameMs) {
        this(cameraIds, barrierWaitMs, postTriggerSettleMs, interWaitFrameMs, true, false);
    }

    public LineSynchronizedCaptureCoordinator(
            Collection<Integer> cameraIds, long barrierWaitMs, long postTriggerSettleMs, long interWaitFrameMs,
            boolean parallelWaitFrame) {
        this(cameraIds, barrierWaitMs, postTriggerSettleMs, interWaitFrameMs, parallelWaitFrame, false);
    }

    public LineSynchronizedCaptureCoordinator(
            Collection<Integer> cameraIds, long barrierWaitMs, long postTriggerSettleMs, long interWaitFrameMs,
            boolean parallelWaitFrame, boolean immediatePrefire) {
        this(cameraIds, barrierWaitMs, postTriggerSettleMs, interWaitFrameMs, parallelWaitFrame, immediatePrefire, false, 1);
    }

    public LineSynchronizedCaptureCoordinator(
            Collection<Integer> cameraIds, long barrierWaitMs, long postTriggerSettleMs, long interWaitFrameMs,
            boolean parallelWaitFrame, boolean immediatePrefire, boolean hardwareLineTrigger) {
        this(cameraIds, barrierWaitMs, postTriggerSettleMs, interWaitFrameMs, parallelWaitFrame, immediatePrefire,
                hardwareLineTrigger, 1);
    }

    public LineSynchronizedCaptureCoordinator(
            Collection<Integer> cameraIds, long barrierWaitMs, long postTriggerSettleMs, long interWaitFrameMs,
            boolean parallelWaitFrame, boolean immediatePrefire, boolean hardwareLineTrigger, int transferWaitWaves) {
        this(cameraIds, barrierWaitMs, postTriggerSettleMs, interWaitFrameMs, parallelWaitFrame, immediatePrefire,
                hardwareLineTrigger, transferWaitWaves, -1L);
    }

    public LineSynchronizedCaptureCoordinator(
            Collection<Integer> cameraIds, long barrierWaitMs, long postTriggerSettleMs, long interWaitFrameMs,
            boolean parallelWaitFrame, boolean immediatePrefire, boolean hardwareLineTrigger, int transferWaitWaves,
            long transferWaveGapMs) {
        LineSynchronizedCaptureComponents c = LineSynchronizedCaptureComponents.create(
                LOG, cameraIds, barrierWaitMs, postTriggerSettleMs, interWaitFrameMs, parallelWaitFrame,
                immediatePrefire, hardwareLineTrigger, transferWaitWaves, transferWaveGapMs);
        this.expectedParties = c.expectedParties;
        this.barrierWaitMs = c.barrierWaitMs;
        this.hardwareLineTrigger = c.hardwareLineTrigger;
        this.lineCaptureExecutor = c.lineCaptureExecutor;
        this.registry = c.registry;
        this.waitFrames = c.waitFrames;
        this.triggerPhase = c.triggerPhase;
        this.barrierLeader = c.barrierLeader;
        this.prefireLatch = c.prefireLatch;
    }

    public void bindWorkers(Map<Integer, WorkerProcessSupervisor> workersByCamera) {
        if (workersByCamera == null || workersByCamera.isEmpty()) {
            this.lineWorkers = Map.of();
            return;
        }
        this.lineWorkers = Map.copyOf(workersByCamera);
    }

    /** Callback на первый usable wait_frame в раунде (камера id). */
    public void setOnFirstFrameCaptured(IntConsumer onFirstFrameCaptured) {
        waitFrames.setOnFirstFrameCaptured(onFirstFrameCaptured);
    }

    /**
     * DI3: немедленный {@code trigger_only} (экспозиция), затем асинхронный {@code wait_frame}+pin.
     * Инспекция не блокирует следующий триггер — кадр копируется в отдельный SHM ({@link LineFramePinService}).
     */
    public void prefireLineTrigger(long triggerSequence, long triggerReceivedEpochMs) {
        prefireLineTrigger(triggerSequence, triggerReceivedEpochMs, null);
    }

    public void prefireLineTrigger(long triggerSequence, long triggerReceivedEpochMs, Collection<Integer> cameraIds) {
        prefireLatch.prefireLineTrigger(triggerSequence, triggerReceivedEpochMs, cameraIds, lineWorkers);
    }

    public boolean isEnabled() {
        return expectedParties > 1;
    }

    public Map<Integer, BinaryProtocol.Message> captureLineBatch(
            long triggerSequence, Map<Integer, WorkerProcessSupervisor> workersByCamera, boolean lenient) {
        return LineCaptureExecutionSupport.captureLineBatch(
                LOG, barrierWaitMs, isEnabled(), triggerPhase, triggerSequence, workersByCamera, lenient);
    }

    public BinaryProtocol.Message captureForLine(
            long triggerSequence, int cameraId, WorkerProcessSupervisor worker) {
        return LineCaptureExecutionSupport.captureForLine(
                LOG, isEnabled(), hardwareLineTrigger, registry, waitFrames, barrierLeader,
                triggerSequence, cameraId, worker);
    }

    @Override
    public void close() {
        registry.closeAll();
        lineCaptureExecutor.shutdownNow();
    }
}
