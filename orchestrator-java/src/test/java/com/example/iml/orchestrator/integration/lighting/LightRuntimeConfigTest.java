package com.example.iml.orchestrator.integration.lighting;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LightRuntimeConfigTest {
    @Test void configurablePersistenceAndWaitsDoNotDependOnProjectLayout() {
        var root = Map.<String, Object>of("light_servers", Map.of("brightness_store_path", "/tmp/line-a/lights.json",
                "runtime", Map.of("command_attempts", 2, "retry_delay_ms", 0, "startup_poll_ms", 17, "status_timeout_ms", 120)));
        var config = LightRuntimeConfig.parse(root);
        assertEquals("/tmp/line-a/lights.json", config.brightnessStorePath());
        assertEquals(2, config.commandAttempts());
        assertEquals(0, config.retryDelayMs());
        assertEquals(17, config.startupPollMs());
        assertEquals(120, config.statusTimeoutMs());
    }
    @Test void invalidRuntimeIsRejectedBeforeNativeWorkersAreStarted() {
        var root = Map.<String, Object>of("light_servers", Map.of("enabled", true,
                "runtime", Map.of("command_attempts", 0)));
        var error = assertThrows(IllegalArgumentException.class, () -> LightTriggerClient.fromRootYaml(root));
        assertTrue(error.getMessage().contains("command_attempts"));
        assertThrows(IllegalArgumentException.class, () -> LightRuntimeConfig.parse(Map.of("light_servers", Map.of("brightness_store_path", ""))));
    }
}
