package com.example.iml.orchestrator.integration.capture.linesync;

import com.example.iml.orchestrator.integration.capture.CaptureException;
import com.example.iml.orchestrator.integration.capture.LineFramePinService;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.IntConsumer;

/**
 * Collect wait_frame results (serial / parallel / transfer waves) and pin into round results.
 */
public final class WaitFrameCollector {

    private static final int WAIT_FRAME_MAX_ATTEMPTS = 1;
    private static final int WAIT_FRAME_MAX_ATTEMPTS_SOFTWARE = 3;
    private static final long WAIT_FRAME_RETRY_MS = 50L;

    private final Logger log;
    private final ExecutorService lineCaptureExecutor;
    private final LineFramePinService framePinService;
    private final boolean parallelWaitFrame;
    private final int transferWaitWaves;
    private final long interWaitFrameMs;
    private final boolean hardwareLineTrigger;
    private final long transferWaveGapMs;
    private volatile IntConsumer onFirstFrameCaptured;

    public WaitFrameCollector(
            Logger log,
            ExecutorService lineCaptureExecutor,
            LineFramePinService framePinService,
            boolean parallelWaitFrame,
            int transferWaitWaves,
            long interWaitFrameMs,
            boolean hardwareLineTrigger,
            long transferWaveGapMs
    ) {
        this.log = log;
        this.lineCaptureExecutor = lineCaptureExecutor;
        this.framePinService = framePinService;
        this.parallelWaitFrame = parallelWaitFrame;
        this.transferWaitWaves = transferWaitWaves;
        this.interWaitFrameMs = interWaitFrameMs;
        this.hardwareLineTrigger = hardwareLineTrigger;
        this.transferWaveGapMs = transferWaveGapMs;
    }

    public void setOnFirstFrameCaptured(IntConsumer onFirstFrameCaptured) {
        this.onFirstFrameCaptured = onFirstFrameCaptured;
    }

    public int collectWaitFrames(
            LineCaptureRound round,
            List<Map.Entry<Integer, WorkerProcessSupervisor>> entries,
            boolean lenient
    ) {
        try {
            if (parallelWaitFrame) {
                if (transferWaitWaves <= 1) {
                    return WaitFrameCollectSupport.collectParallel(
                            log, lineCaptureExecutor, framePinService, this, round, entries, lenient);
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
                    log.info(
                            "sync_diag channel=inspect event=line_wait_wave wave={}/{} cameras={}",
                            wave + 1,
                            transferWaitWaves,
                            waveEntries.size()
                    );
                    okCount += WaitFrameCollectSupport.collectParallel(
                            log, lineCaptureExecutor, framePinService, this, round, waveEntries, lenient);
                    if (wave + 1 < transferWaitWaves) {
                        long waveGapMs = Math.max(interWaitFrameMs, transferWaveGapMs);
                        if (waveGapMs > 0L) {
                            Thread.sleep(waveGapMs);
                        }
                    }
                }
                return okCount;
            }
            return WaitFrameCollectSupport.collectSerial(
                    log, framePinService, this, round, entries, lenient, interWaitFrameMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CaptureException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CaptureException(e);
        }
    }

    public BinaryProtocol.Message waitFrameForCamera(
            LineCaptureRound round,
            int cameraId,
            WorkerProcessSupervisor worker
    ) {
        BinaryProtocol.Message existing = round.results.get(cameraId);
        if (CaptureUsability.isUsableCapture(existing)) {
            return existing;
        }
        BinaryProtocol.Message msg = waitFrameWithRetry(worker, cameraId);
        if (!CaptureUsability.isUsableCapture(msg)) {
            throw new IllegalStateException(
                    "line capture unusable cam=" + cameraId + ": " + CaptureUsability.describeCapture(msg)
            );
        }
        BinaryProtocol.Message pinned = framePinService.pinCapture(msg, cameraId);
        storeUsableFrame(round, cameraId, pinned);
        return pinned;
    }

    public BinaryProtocol.Message waitFrameWithRetry(WorkerProcessSupervisor worker, int cameraId) {
        return WaitFrameCollectSupport.waitFrameWithRetry(
                log,
                hardwareLineTrigger,
                worker,
                cameraId,
                WAIT_FRAME_MAX_ATTEMPTS,
                WAIT_FRAME_MAX_ATTEMPTS_SOFTWARE,
                WAIT_FRAME_RETRY_MS
        );
    }

    public void storeUsableFrame(LineCaptureRound round, int cameraId, BinaryProtocol.Message pinned) {
        round.results.put(cameraId, pinned);
        notifyFirstFrameIfNeeded(round, cameraId);
    }

    private void notifyFirstFrameIfNeeded(LineCaptureRound round, int cameraId) {
        if (!round.firstFrameNotified.compareAndSet(false, true)) {
            return;
        }
        IntConsumer cb = onFirstFrameCaptured;
        if (cb == null) {
            return;
        }
        try {
            cb.accept(cameraId);
        } catch (Exception e) {
            log.warn("onFirstFrameCaptured cam={}: {}", cameraId, e.getMessage());
        }
    }
}
