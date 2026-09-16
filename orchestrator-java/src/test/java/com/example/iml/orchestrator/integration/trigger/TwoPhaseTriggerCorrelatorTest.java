package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.trigger.config.TwoPhaseTriggerConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TwoPhaseTriggerCorrelatorTest {
    private static final TwoPhaseTriggerConfig ENABLED = new TwoPhaseTriggerConfig(true, 700, 150);
    private static final Instant T0 = Instant.parse("2026-08-19T07:00:00Z");

    @Test
    void acceptsFirstTwoPulsesRegardlessOfDelay() {
        TwoPhaseTriggerCorrelator correlator = new TwoPhaseTriggerCorrelator(ENABLED);

        assertAssignment(correlator.correlate(41, T0), 0, 41, 41);
        assertAssignment(correlator.correlate(42, T0.plusMillis(120)), 1, 41, 42);
    }

    @Test
    void discardsEverythingAfterSecondPulseUntilDirectionWindowResets() {
        TwoPhaseTriggerCorrelator correlator = new TwoPhaseTriggerCorrelator(ENABLED);

        assertAssignment(correlator.correlate(10, T0), 0, 10, 10);
        assertAssignment(correlator.correlate(11, T0.plusMillis(999)), 1, 10, 11);
        assertNull(correlator.correlate(12, T0.plusMillis(1_100)));
        assertNull(correlator.correlate(13, T0.plusMillis(5_000)));
    }

    @Test
    void directionChangeStartsFreshTwoPulseWindow() {
        TwoPhaseTriggerCorrelator correlator = new TwoPhaseTriggerCorrelator(ENABLED);

        assertAssignment(correlator.correlate(20, T0), 0, 20, 20);
        assertAssignment(correlator.correlate(21, T0.plusMillis(300)), 1, 20, 21);
        correlator.resetDirectionWindow();
        assertAssignment(correlator.correlate(22, T0.plusMillis(2_000)), 0, 22, 22);
        assertAssignment(correlator.correlate(23, T0.plusMillis(2_050)), 1, 22, 23);
    }

    @Test
    void disabledModeTreatsEveryPulseAsIndependentPhaseZero() {
        TwoPhaseTriggerCorrelator correlator =
                new TwoPhaseTriggerCorrelator(TwoPhaseTriggerConfig.defaults());

        assertAssignment(correlator.correlate(30, T0), 0, 30, 30);
        assertAssignment(correlator.correlate(31, T0.plusMillis(700)), 0, 31, 31);
    }

    private static void assertAssignment(
            TwoPhaseTriggerCorrelator.PhaseAssignment actual,
            int phaseId,
            long parentCycleId,
            long rawTriggerSequence
    ) {
        assertEquals(phaseId, actual.phaseId());
        assertEquals(parentCycleId, actual.parentCycleId());
        assertEquals(rawTriggerSequence, actual.rawTriggerSequence());
    }
}
