package com.example.iml.orchestrator.integration.trigger.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoPhaseTriggerConfigTest {

    @Test
    void defaultsAreBackwardCompatibleAndDisabled() {
        InspectionTriggerConfig config = InspectionTriggerConfig.parse(Map.of());

        assertFalse(config.twoPhase().enabled());
        assertEquals(700, config.twoPhase().expectedDelayMs());
        assertEquals(150, config.twoPhase().toleranceMs());
        assertEquals(0, config.twoPhase().autoSecondPhaseDelayMs());
    }

    @Test
    void parsesTwoPhaseTiming() {
        InspectionTriggerConfig config = InspectionTriggerConfig.parse(Map.of(
                "inspection_trigger",
                Map.of(
                        "two_phase",
                        Map.of(
                                "enabled", true,
                                "expected_delay_ms", 700,
                                "tolerance_ms", 150
                        )
                )
        ));

        assertTrue(config.twoPhase().enabled());
        assertEquals(700, config.twoPhase().expectedDelayMs());
        assertEquals(150, config.twoPhase().toleranceMs());
        assertEquals(80, config.twoPhase().autoSecondPhaseDelayMs());
        assertEquals(75, config.twoPhase().fallbackPhysicalGraceMs());
        assertFalse(config.twoPhase().singleDi3Burst());
    }

    @Test
    void parsesSingleDi3BurstDelay() {
        InspectionTriggerConfig config = InspectionTriggerConfig.parse(Map.of(
                "inspection_trigger",
                Map.of(
                        "two_phase",
                        Map.of(
                                "enabled", true,
                                "single_di3_burst", true,
                                "burst_delay_ms", 76
                        )
                )
        ));

        assertTrue(config.twoPhase().singleDi3Burst());
        assertEquals(76, config.twoPhase().autoSecondPhaseDelayMs());
    }

    @Test
    void autoSecondPhaseDelayCanBeDisabledExplicitly() {
        InspectionTriggerConfig config = InspectionTriggerConfig.parse(Map.of(
                "inspection_trigger",
                Map.of(
                        "two_phase",
                        Map.of(
                                "enabled", true,
                                "auto_second_phase_delay_ms", 0
                        )
                )
        ));

        assertEquals(0, config.twoPhase().autoSecondPhaseDelayMs());
    }
}
