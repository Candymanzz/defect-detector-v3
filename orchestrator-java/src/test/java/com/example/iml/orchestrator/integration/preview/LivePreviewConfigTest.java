package com.example.iml.orchestrator.integration.preview;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivePreviewConfigTest {

    @Test
    void disabledWhenRootMissing() {
        LivePreviewConfig config = LivePreviewConfig.fromRootYaml(null);

        assertFalse(config.enabled());
        assertEquals(10, config.maxFps());
    }

    @Test
    void parsesClientAndIntegrationSections() {
        Map<String, Object> root = Map.of(
                "client", Map.of("preview_max_fps", 15),
                "integration", Map.of(
                        "live_preview", Map.of(
                                "enabled", true,
                                "flash_on_tick", true,
                                "preview_max_fps", 20,
                                "interval_ms", 500
                        )
                )
        );

        LivePreviewConfig config = LivePreviewConfig.fromRootYaml(root);

        assertTrue(config.enabled());
        assertTrue(config.flashOnTick());
        assertEquals(20, config.maxFps());
        assertEquals(500, config.tickIntervalMs());
    }

    @Test
    void tickIntervalFallsBackToFps() {
        LivePreviewConfig config = new LivePreviewConfig(true, false, 5, 0);

        assertEquals(1000, config.tickIntervalMs());
    }

    @Test
    void usesDevAutoTriggerStubIntervalWhenConfigured() {
        Map<String, Object> root = Map.of(
                "integration", Map.of(
                        "dev_auto_trigger_stub", Map.of("enabled", true, "interval_ms", 1200)
                )
        );

        LivePreviewConfig config = LivePreviewConfig.fromRootYaml(root);

        assertEquals(1200, config.tickIntervalMs());
    }
}
