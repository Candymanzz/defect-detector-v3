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
    }
}
