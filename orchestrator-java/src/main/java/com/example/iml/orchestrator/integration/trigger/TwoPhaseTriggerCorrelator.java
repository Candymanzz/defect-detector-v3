package com.example.iml.orchestrator.integration.trigger;



import com.example.iml.orchestrator.integration.trigger.config.TwoPhaseTriggerConfig;



import java.time.Instant;

import java.util.Objects;



/**

 * Legacy: два DI3↑ в окне DI2=1 → phase 0 и 1.

 * {@code single_di3_burst}: один DI3 → phase 0; phase 1 назначает оркестратор по таймеру.

 */

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

        if (config.singleDi3Burst()) {

            if (acceptedPulses >= 1) {

                return null;

            }

            parentCycleId = rawTriggerSequence;

            acceptedPulses = 1;

            return new PhaseAssignment(0, parentCycleId, rawTriggerSequence);

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



    /** Phase 1 burst после таймера (тот же parent_cycle, новый raw_seq). */

    public synchronized PhaseAssignment assignBurstPhase1(long rawTriggerSequence, Instant receivedAt) {

        if (rawTriggerSequence <= 0L) {

            throw new IllegalArgumentException("rawTriggerSequence must be positive");

        }

        Objects.requireNonNull(receivedAt, "receivedAt");

        if (!config.enabled() || !config.singleDi3Burst()) {

            throw new IllegalStateException("burst phase1 requires single_di3_burst");

        }

        if (acceptedPulses < 1 || parentCycleId <= 0L) {

            return null;

        }

        if (acceptedPulses >= 2) {

            return null;

        }

        acceptedPulses = 2;

        return new PhaseAssignment(1, parentCycleId, rawTriggerSequence);

    }



    /** DI2 изменился: следующее окно снова принимает импульсы. */

    public synchronized void resetDirectionWindow() {

        parentCycleId = 0L;

        acceptedPulses = 0;

    }



    /** Сколько DI3↑ уже принято в текущем окне DI2=1 (0, 1 или 2). */

    public synchronized int acceptedPulseCount() {

        return acceptedPulses;

    }



    public record PhaseAssignment(int phaseId, long parentCycleId, long rawTriggerSequence) {

    }

}


