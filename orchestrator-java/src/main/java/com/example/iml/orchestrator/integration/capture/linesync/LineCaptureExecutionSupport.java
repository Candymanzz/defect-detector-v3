package com.example.iml.orchestrator.integration.capture.linesync;

import com.example.iml.orchestrator.integration.capture.CaptureException;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** captureLineBatch / captureForLine barrier paths. */
public final class LineCaptureExecutionSupport {

    private static final long FIRE_DONE_WAIT_MS = 30_000L;
    private static final long FRAMES_READY_WAIT_MS = 30_000L;

    private LineCaptureExecutionSupport() {
    }

    public static Map<Integer, BinaryProtocol.Message> captureLineBatch(
            Logger log,
            long barrierWaitMs,
            boolean enabled,
            LineTriggerPhase triggerPhase,
            long triggerSequence,
            Map<Integer, WorkerProcessSupervisor> workersByCamera,
            boolean lenient
    ) throws CaptureException {
        if (workersByCamera == null || workersByCamera.isEmpty()) {
            return Map.of();
        }
        if (!enabled || triggerSequence <= 0L) {
            Map<Integer, BinaryProtocol.Message> solo = new LinkedHashMap<>();
            try {
                for (Map.Entry<Integer, WorkerProcessSupervisor> entry : workersByCamera.entrySet()) {
                    solo.put(entry.getKey(), entry.getValue().command(Map.of("op", "capture", "sync", true)));
                }
            } catch (IOException e) {
                throw new CaptureException(e);
            }
            return solo;
        }
        LineCaptureRound round = new LineCaptureRound(barrierWaitMs);
        List<Integer> sorted = workersByCamera.keySet().stream().sorted().toList();
        for (Integer cameraId : sorted) {
            WorkerProcessSupervisor worker = workersByCamera.get(cameraId);
            if (worker != null) {
                round.participants.put(cameraId, worker);
            }
        }
        triggerPhase.fireLineCapture(round, lenient);
        Map<Integer, BinaryProtocol.Message> captured = new LinkedHashMap<>();
        for (Integer cameraId : sorted) {
            BinaryProtocol.Message msg = round.results.get(cameraId);
            if (!CaptureUsability.isUsableCapture(msg)) {
                if (lenient) {
                    continue;
                }
                throw new IllegalStateException(
                        "line batch cam=" + cameraId + " unusable: " + CaptureUsability.describeCapture(msg)
                );
            }
            captured.put(cameraId, msg);
        }
        if (captured.isEmpty()) {
            throw new IllegalStateException("line batch seq=" + triggerSequence + " produced no usable frames");
        }
        log.info(
                "line capture batch complete seq={} cameras={}/{} lenient={}",
                triggerSequence,
                captured.size(),
                sorted.size(),
                lenient
        );
        return captured;
    }

    public static BinaryProtocol.Message captureForLine(
            Logger log,
            boolean enabled,
            boolean hardwareLineTrigger,
            LineRoundRegistry registry,
            WaitFrameCollector waitFrames,
            LineBarrierLeader barrierLeader,
            long triggerSequence,
            int cameraId,
            WorkerProcessSupervisor worker
    ) throws CaptureException {
        if (!enabled || triggerSequence <= 0L) {
            try {
                return worker.command(Map.of("op", "capture", "sync", true));
            } catch (IOException e) {
                throw new CaptureException(e);
            }
        }
        LineCaptureRound round = registry.get(triggerSequence);
        if (round == null) {
            round = registry.getOrCreate(triggerSequence);
        }
        if (round.triggerPrefired.get()) {
            awaitLatch(round.framesReady, FRAMES_READY_WAIT_MS, "line latch timed out cam=" + cameraId
                    + " seq=" + triggerSequence);
            rethrowRoundFailure(round);
            BinaryProtocol.Message capture = round.results.get(cameraId);
            if (!CaptureUsability.isUsableCapture(capture)) {
                throw new IllegalStateException(
                        "line latched frame missing cam=" + cameraId + " seq=" + triggerSequence + ": "
                                + CaptureUsability.describeCapture(capture)
                );
            }
            round.consumedFrames.incrementAndGet();
            long frameId = YamlScalars.toLong(capture.header().get("frame_id"), -1L);
            log.debug(
                    "sync_diag channel=inspect event=line_frame_from_latch cam={} seq={} frame_id={}",
                    cameraId,
                    triggerSequence,
                    frameId
            );
            return capture;
        }

        round.arrive(cameraId, worker);

        if (hardwareLineTrigger) {
            BinaryProtocol.Message capture = waitFrames.waitFrameForCamera(round, cameraId, worker);
            round.releaseParticipant();
            if (!CaptureUsability.isUsableCapture(capture)) {
                throw new IllegalStateException(
                        "line wait_frame unusable cam=" + cameraId + " seq=" + triggerSequence + ": "
                                + CaptureUsability.describeCapture(capture)
                );
            }
            long frameId = YamlScalars.toLong(capture.header().get("frame_id"), -1L);
            log.debug(
                    "sync_diag channel=inspect event=line_frame_from_hw cam={} seq={} frame_id={}",
                    cameraId,
                    triggerSequence,
                    frameId
            );
            return capture;
        }

        barrierLeader.awaitBarrier(round);
        barrierLeader.fireIfLeader(round, triggerSequence);
        if (!awaitLatchQuiet(round.fireDone, FIRE_DONE_WAIT_MS)) {
            round.releaseParticipant();
            throw new IllegalStateException("line capture timed out waiting for fire cam=" + cameraId);
        }
        if (round.failure != null) {
            round.releaseParticipant();
            rethrowRoundFailure(round);
        }
        BinaryProtocol.Message capture = round.results.get(cameraId);
        round.releaseParticipant();
        if (!CaptureUsability.isUsableCapture(capture)) {
            throw new IllegalStateException(
                    "line capture unusable cam=" + cameraId + " seq=" + triggerSequence + ": "
                            + CaptureUsability.describeCapture(capture)
            );
        }
        return capture;
    }

    private static void awaitLatch(
            java.util.concurrent.CountDownLatch latch,
            long timeoutMs,
            String timeoutMessage
    ) throws CaptureException {
        if (!awaitLatchQuiet(latch, timeoutMs)) {
            throw new IllegalStateException(timeoutMessage);
        }
    }

    private static boolean awaitLatchQuiet(java.util.concurrent.CountDownLatch latch, long timeoutMs)
            throws CaptureException {
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CaptureException(e);
        }
    }

    private static void rethrowRoundFailure(LineCaptureRound round) throws CaptureException {
        Exception failure = round.failure;
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new CaptureException(failure);
    }
}
