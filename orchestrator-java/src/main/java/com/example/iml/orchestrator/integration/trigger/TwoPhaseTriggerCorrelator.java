package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.trigger.config.TwoPhaseTriggerConfig;

import java.time.Instant;
import java.util.Objects;

/** Принимает ровно два первых DI3↑ в одном окне DI2=1 как фазы 0 и 1. */
public final class TwoPhaseTriggerCorrelator {
    private final TwoPhaseTriggerConfig config;
    private long parentCycleId;
    private int acceptedPulses;

    public TwoPhaseTriggerCorrelator(TwoPhaseTriggerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public synchronized PhaseAssignment correlate(long rawTriggerSequence, Instant receivedAt) {
        if (rawTriggerSequence <= 0L) {
            throw new IllegalArgumentException("rawTriggerSequence must be positive");
        }
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (!config.enabled()) {
            return new PhaseAssignment(0, rawTriggerSequence, rawTriggerSequence);
        }
        if (acceptedPulses >= 2) {
            return null;
        }
        if (acceptedPulses == 0) {
            parentCycleId = rawTriggerSequence;
        }
        int phaseId = acceptedPulses++;
        return new PhaseAssignment(phaseId, parentCycleId, rawTriggerSequence);
    }

    /** DI2 изменился: следующее окно снова принимает первые два DI3↑. */
    public synchronized void resetDirectionWindow() {
        parentCycleId = 0L;
        acceptedPulses = 0;
    }

    public record PhaseAssignment(int phaseId, long parentCycleId, long rawTriggerSequence) {
    }
}
