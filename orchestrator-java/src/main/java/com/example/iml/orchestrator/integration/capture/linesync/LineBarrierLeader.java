package com.example.iml.orchestrator.integration.capture.linesync;

import com.example.iml.orchestrator.integration.capture.CaptureException;
import org.apache.logging.log4j.Logger;

/**
 * Soft barrier wait + single-leader fire for software line-sync capture.
 */
public final class LineBarrierLeader {

    private static final long BARRIER_POLL_MS = 2L;

    private final Logger log;
    private final int expectedParties;
    private final LineTriggerPhase triggerPhase;

    public LineBarrierLeader(Logger log, int expectedParties, LineTriggerPhase triggerPhase) {
        this.log = log;
        this.expectedParties = expectedParties;
        this.triggerPhase = triggerPhase;
    }

    public void awaitBarrier(LineCaptureRound round) throws CaptureException {
        synchronized (round.awaitLock) {
            while (round.participants.size() < expectedParties) {
                long remaining = round.deadlineMs - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                try {
                    round.awaitLock.wait(Math.min(BARRIER_POLL_MS, remaining));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CaptureException(e);
                }
            }
        }
    }

    public void fireIfLeader(LineCaptureRound round, long triggerSequence) {
        if (!round.fired.compareAndSet(false, true)) {
            return;
        }
        int arrived = round.participants.size();
        long barrierSpreadMs = round.firstArriveMs > 0 && round.lastArriveMs >= round.firstArriveMs
                ? round.lastArriveMs - round.firstArriveMs
                : -1L;
        if (arrived < expectedParties) {
            log.warn(
                    "line capture partial barrier seq={} arrived={}/{} spread_ms={} — firing for participants present",
                    triggerSequence,
                    arrived,
                    expectedParties,
                    barrierSpreadMs
            );
        } else if (barrierSpreadMs >= 0) {
            log.info(
                    "sync_diag channel=inspect event=line_barrier_ready trigger_sequence={} cameras={} barrier_spread_ms={}",
                    triggerSequence,
                    arrived,
                    barrierSpreadMs
            );
        }
        try {
            triggerPhase.fireLineCapture(round, false);
        } catch (Exception e) {
            round.failure = e;
        } finally {
            round.fireDone.countDown();
        }
    }
}
