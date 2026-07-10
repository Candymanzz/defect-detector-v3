package com.example.iml.orchestrator.integration.trigger.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoInputDiscreteConfigTest {

    @Test
    void parsesDirectionInvertFromIntegrationConfig() {
        Map<String, Object> integration = Map.of(
                "inspection_trigger",
                Map.of(
                        "io_input",
                        Map.of("direction_invert", true)
                )
        );

        IoInputDiscreteConfig cfg = IoInputDiscreteConfig.parse(integration, 0);

        assertTrue(cfg.directionInvert());
    }

    @Test
    void di3OnlyDisablesWorkAndDirectionRequirements() {
        Map<String, Object> integration = Map.of(
                "inspection_trigger",
                Map.of(
                        "io_input",
                        Map.of("di3_only", true)
                )
        );

        IoInputDiscreteConfig cfg = IoInputDiscreteConfig.parse(integration, 0);

        assertTrue(cfg.di3Only());
        assertFalse(cfg.requireDirection());
        assertFalse(cfg.requireWork());
        assertEquals(TriggerEdgeMode.RISING, cfg.triggerEdge());
    }

    @Test
    void di3OnlyForcesRisingEvenWhenConfigSaysFalling() {
        Map<String, Object> integration = Map.of(
                "inspection_trigger",
                Map.of(
                        "io_input",
                        Map.of("di3_only", true, "trigger_edge", "falling")
                )
        );

        IoInputDiscreteConfig cfg = IoInputDiscreteConfig.parse(integration, 0);

        assertEquals(TriggerEdgeMode.RISING, cfg.triggerEdge());
    }
}
