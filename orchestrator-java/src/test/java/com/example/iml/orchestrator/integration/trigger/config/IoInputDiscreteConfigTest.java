package com.example.iml.orchestrator.integration.trigger.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
    void directionInvertDefaultsToFalse() {
        IoInputDiscreteConfig cfg = IoInputDiscreteConfig.parse(Map.of(), 0);
        assertFalse(cfg.directionInvert());
    }
}
