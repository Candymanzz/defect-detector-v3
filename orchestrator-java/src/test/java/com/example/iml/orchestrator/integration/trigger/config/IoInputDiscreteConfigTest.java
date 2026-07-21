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
    void directionLatchOnWorkDefaultsRequireWorkTrue() {
        Map<String, Object> integration = Map.of(
                "inspection_trigger",
                Map.of(
                        "io_input",
                        Map.of("direction_latch_on_work", true)
                )
        );

        IoInputDiscreteConfig cfg = IoInputDiscreteConfig.parse(integration, 0);

        assertTrue(cfg.directionLatchOnWork());
        assertTrue(cfg.requireWork());
        assertTrue(cfg.requireDirection());
    }

    @Test
    void di3OnlyKeepsDirectionRequirementWhenConfigured() {
        Map<String, Object> integration = Map.of(
                "inspection_trigger",
                Map.of(
                        "io_input",
                        Map.of(
                                "di3_only", true,
                                "require_direction", true,
                                "require_work", false
                        )
                )
        );

        IoInputDiscreteConfig cfg = IoInputDiscreteConfig.parse(integration, 0);

        assertTrue(cfg.di3Only());
        assertTrue(cfg.requireDirection());
        assertFalse(cfg.requireWork());
        assertEquals(TriggerEdgeMode.RISING, cfg.triggerEdge());
    }

    @Test
    void parsesCaptureDelayMsFromIntegrationConfig() {
        Map<String, Object> integration = Map.of(
                "inspection_trigger",
                Map.of(
                        "io_input",
                        Map.of("capture_delay_ms", 350)
                )
        );

        IoInputDiscreteConfig cfg = IoInputDiscreteConfig.parse(integration, 0);

        assertEquals(350, cfg.captureDelayMs());
    }

    @Test
    void di3OnlyRespectsConfiguredTriggerEdge() {
        Map<String, Object> integration = Map.of(
                "inspection_trigger",
                Map.of(
                        "io_input",
                        Map.of("di3_only", true, "trigger_edge", "falling")
                )
        );

        IoInputDiscreteConfig cfg = IoInputDiscreteConfig.parse(integration, 0);

        assertEquals(TriggerEdgeMode.FALLING, cfg.triggerEdge());
    }

    @Test
    void directionLatchDefaultsTrue() {
        IoInputDiscreteConfig cfg = IoInputDiscreteConfig.defaults();
        assertTrue(cfg.directionLatch());
    }

    @Test
    void parsesDirectionLatchFromIntegrationConfig() {
        Map<String, Object> integration = Map.of(
                "inspection_trigger",
                Map.of(
                        "io_input",
                        Map.of("direction_latch", false)
                )
        );

        IoInputDiscreteConfig cfg = IoInputDiscreteConfig.parse(integration, 0);

        assertFalse(cfg.directionLatch());
    }

    @Test
    void parsesExternalHardwareCaptureFromIntegrationConfig() {
        Map<String, Object> integration = Map.of(
                "inspection_trigger",
                Map.of(
                        "io_input",
                        Map.of("external_hardware_capture", true)
                )
        );

        IoInputDiscreteConfig cfg = IoInputDiscreteConfig.parse(integration, 0);

        assertTrue(cfg.externalHardwareCapture());
    }
}
