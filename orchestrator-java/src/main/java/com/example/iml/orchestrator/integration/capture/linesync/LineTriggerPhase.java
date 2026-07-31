package com.example.iml.orchestrator.integration.capture.linesync;

import com.example.iml.orchestrator.integration.capture.CaptureException;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Dispatch trigger_only and orchestrate fire → wait_frame for a line round. */
public final class LineTriggerPhase {

    private final Logger log;
    private final ExecutorService lineCaptureExecutor;
    private final WaitFrameCollector waitFrames;
    private final boolean hardwareLineTrigger;
    private final long postTriggerSettleMs;
    private final boolean parallelWaitFrame;

    public LineTriggerPhase(
            Logger log,
            ExecutorService lineCaptureExecutor,
            WaitFrameCollector waitFrames,
            boolean hardwareLineTrigger,
            long postTriggerSettleMs,
            boolean parallelWaitFrame
    ) {
        this.log = log;
        this.lineCaptureExecutor = lineCaptureExecutor;
        this.waitFrames = waitFrames;
        this.hardwareLineTrigger = hardwareLineTrigger;
        this.postTriggerSettleMs = postTriggerSettleMs;
        this.parallelWaitFrame = parallelWaitFrame;
    }

    public void dispatchTriggerOnly(
            LineCaptureRound round,
            Map<Integer, WorkerProcessSupervisor> workers
    ) {
        List<Map.Entry<Integer, WorkerProcessSupervisor>> entries = new ArrayList<>(workers.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (Map.Entry<Integer, WorkerProcessSupervisor> entry : entries) {
            round.participants.putIfAbsent(entry.getKey(), entry.getValue());
        }
        List<Callable<Void>> triggerTasks = new ArrayList<>(entries.size());
        maping(entries, triggerTasks);
    }

    public void fireLineCapture(LineCaptureRound round, boolean lenient) {
        try {
            long t0 = System.nanoTime();
            List<Map.Entry<Integer, WorkerProcessSupervisor>> entries =
                    new ArrayList<>(round.participants.entrySet());
            entries.sort(Map.Entry.comparingByKey());

            List<Callable<Void>> triggerTasks = new ArrayList<>(entries.size());
            if (!hardwareLineTrigger && !round.triggerPrefired.get()) {
                maping(entries, triggerTasks);
                if (postTriggerSettleMs > 0) {
                    Thread.sleep(postTriggerSettleMs);
                }
            }

            long tTriggerDone = System.nanoTime();
            if (round.triggerPrefired.get()) {
                log.info(
                        "sync_diag channel=inspect event=line_trigger_only_dispatched cameras={} trigger_phase_ms=0 prefire=true",
                        entries.size()
                );
                if (postTriggerSettleMs > 0) {
                    Thread.sleep(postTriggerSettleMs);
                }
            } else {
                log.info(
                        "sync_diag channel=inspect event=line_trigger_only_dispatched cameras={} trigger_phase_ms={}",
                        entries.size(),
                        (tTriggerDone - t0) / 1_000_000L
                );
            }
            int okCount = waitFrames.collectWaitFrames(round, entries, lenient);

            log.info(
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CaptureException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CaptureException(e);
        }
    }

    private void maping(
            List<Map.Entry<Integer, WorkerProcessSupervisor>> entries,
            List<Callable<Void>> triggerTasks
    ) {
        for (Map.Entry<Integer, WorkerProcessSupervisor> entry : entries) {
            WorkerProcessSupervisor worker = entry.getValue();
            triggerTasks.add(() -> {
                worker.command(Map.of("op", "capture", "trigger_only", true));
                return null;
            });
        }
        invokeAll(triggerTasks);
    }

    private void invokeAll(List<Callable<Void>> tasks) {
        try {
            List<Future<Void>> futures = lineCaptureExecutor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CaptureException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CaptureException(e);
        }
    }
}
