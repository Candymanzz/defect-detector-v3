package com.example.iml.orchestrator.integration.capture.linesync;

import com.example.iml.orchestrator.integration.capture.LineFramePinService;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

/** Registry of in-flight line capture rounds with pin cleanup on prune/close. */
public final class LineRoundRegistry {

    private final Logger log;
    private final long barrierWaitMs;
    private final int expectedParties;
    private final ConcurrentHashMap<Long, LineCaptureRound> rounds = new ConcurrentHashMap<>();

    public LineRoundRegistry(Logger log, long barrierWaitMs, int expectedParties) {
        this.log = log;
        this.barrierWaitMs = barrierWaitMs;
        this.expectedParties = expectedParties;
    }

    public LineCaptureRound get(long triggerSequence) {
        return rounds.get(triggerSequence);
    }

    public LineCaptureRound getOrCreate(long triggerSequence) {
        return rounds.computeIfAbsent(triggerSequence, ignored -> new LineCaptureRound(barrierWaitMs));
    }

    public void pruneOldRounds(long currentSequence) {
        long cutoff = currentSequence - 32L;
        if (cutoff <= 0L) {
            return;
        }
        rounds.entrySet().removeIf(entry -> {
            if (entry.getKey() >= cutoff) {
                return false;
            }
            LineCaptureRound round = entry.getValue();
            // Always release pins — incomplete/timed-out rounds otherwise retain full BGR forever.
            for (BinaryProtocol.Message pinned : round.results.values()) {
                if (pinned != null && pinned.header() != null) {
                    LineFramePinService.releasePinnedCapture(pinned.header());
                }
            }
            if (round.consumedFrames.get() < expectedParties) {
                log.debug(
                        "line capture prune incomplete round seq={} consumed={}/{}",
                        entry.getKey(),
                        round.consumedFrames.get(),
                        expectedParties
                );
            }
            return true;
        });
    }

    public void closeAll() {
        for (LineCaptureRound round : rounds.values()) {
            for (BinaryProtocol.Message pinned : round.results.values()) {
                if (pinned != null && pinned.header() != null) {
                    LineFramePinService.releasePinnedCapture(pinned.header());
                }
            }
        }
        rounds.clear();
    }
}
