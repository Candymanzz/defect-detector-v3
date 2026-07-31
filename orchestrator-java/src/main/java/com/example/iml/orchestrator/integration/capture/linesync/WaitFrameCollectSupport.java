package com.example.iml.orchestrator.integration.capture.linesync;

import com.example.iml.orchestrator.integration.capture.CaptureException;
import com.example.iml.orchestrator.integration.capture.LineFramePinService;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Parallel / serial wait_frame collection helpers. */
final class WaitFrameCollectSupport {

    private WaitFrameCollectSupport() {
    }

    static int collectSerial(
            Logger log,
            LineFramePinService framePinService,
            WaitFrameCollector owner,
            LineCaptureRound round,
            List<Map.Entry<Integer, WorkerProcessSupervisor>> entries,
            boolean lenient,
            long interWaitFrameMs
    ) throws InterruptedException {
        int okCount = 0;
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<Integer, WorkerProcessSupervisor> entry = entries.get(i);
            int camId = entry.getKey();
            WorkerProcessSupervisor worker = entry.getValue();
            BinaryProtocol.Message msg = owner.waitFrameWithRetry(worker, camId);
            if (!CaptureUsability.isUsableCapture(msg)) {
                if (lenient) {
                    log.warn(
                            "line capture cam={} skipped (lenient): {}",
                            camId,
                            CaptureUsability.describeCapture(msg));
                    continue;
                }
                throw new IllegalStateException(
                        "cam=" + camId + " wait_frame failed: " + CaptureUsability.describeCapture(msg));
            }
            owner.storeUsableFrame(round, camId, framePinService.pinCapture(msg, camId));
            okCount++;
            if (interWaitFrameMs > 0 && i + 1 < entries.size()) {
                Thread.sleep(interWaitFrameMs);
            }
        }
        return okCount;
    }

    static int collectParallel(
            Logger log,
            ExecutorService lineCaptureExecutor,
            LineFramePinService framePinService,
            WaitFrameCollector owner,
            LineCaptureRound round,
            List<Map.Entry<Integer, WorkerProcessSupervisor>> entries,
            boolean lenient
    ) throws InterruptedException, ExecutionException {
        List<Callable<Integer>> waitTasks = new ArrayList<>(entries.size());
        for (Map.Entry<Integer, WorkerProcessSupervisor> entry : entries) {
            int camId = entry.getKey();
            WorkerProcessSupervisor worker = entry.getValue();
            waitTasks.add(() -> {
                BinaryProtocol.Message msg = owner.waitFrameWithRetry(worker, camId);
                if (!CaptureUsability.isUsableCapture(msg)) {
                    if (lenient) {
                        log.warn(
                                "line capture cam={} skipped (lenient): {}",
                                camId,
                                CaptureUsability.describeCapture(msg)
                        );
                        return 0;
                    }
                    throw new IllegalStateException(
                            "cam=" + camId + " wait_frame failed: " + CaptureUsability.describeCapture(msg));
                }
                owner.storeUsableFrame(round, camId, framePinService.pinCapture(msg, camId));
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

    static BinaryProtocol.Message waitFrameWithRetry(
            Logger log,
            boolean hardwareLineTrigger,
            WorkerProcessSupervisor worker,
            int cameraId,
            int maxAttemptsHw,
            int maxAttemptsSw,
            long retryMs
    ) {
        try {
            BinaryProtocol.Message last = null;
            int maxAttempts = hardwareLineTrigger ? maxAttemptsHw : maxAttemptsSw;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                last = worker.command(Map.of("op", "capture", "wait_frame", true));
                if (CaptureUsability.isUsableCapture(last)) {
                    return last;
                }
                log.warn(
                        "line capture cam={} wait_frame attempt {}/{} unusable: {}",
                        cameraId,
                        attempt,
                        maxAttempts,
                        CaptureUsability.describeCapture(last)
                );
                if (attempt < maxAttempts) {
                    long sleepMs = hardwareLineTrigger ? 0L : retryMs;
                    if (sleepMs > 0L) {
                        Thread.sleep(sleepMs);
                    }
                }
            }
            return last;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CaptureException(e);
        } catch (IOException e) {
            throw new CaptureException(e);
        }
    }
}
