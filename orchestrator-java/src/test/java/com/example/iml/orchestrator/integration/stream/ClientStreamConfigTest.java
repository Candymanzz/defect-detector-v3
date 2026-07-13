package com.example.iml.orchestrator.integration.stream;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientStreamConfigTest {

    @Test
    void defaultsWhenRootMissing() {
        ClientStreamConfig config = ClientStreamConfig.fromRootYaml(null);
        assertEquals(20, config.defaultMaxFps());
        assertEquals(30, config.maxFpsCap());
    }

    @Test
    void parsesIntegrationBlockAndCapsAtThirty() {
        Map<String, Object> root = Map.of(
                "integration",
                Map.of("client_stream", Map.of("default_max_fps", 15, "max_fps_cap", 60))
        );

        ClientStreamConfig config = ClientStreamConfig.fromRootYaml(root);

        assertEquals(15, config.defaultMaxFps());
        assertEquals(30, config.maxFpsCap());
    }

    @Test
    void clampFpsUsesDefaultsAndCap() {
        ClientStreamConfig config = new ClientStreamConfig(10, 25);

        assertEquals(10, config.clampFps(0));
        assertEquals(25, config.clampFps(100));
        assertEquals(18, config.clampFps(18));
    }

    @Test
    void maxFpsCapNeverBelowDefaultFps() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> integration = new LinkedHashMap<>();
        integration.put("client_stream", Map.of("default_max_fps", 25, "max_fps_cap", 10));
        root.put("integration", integration);

        ClientStreamConfig config = ClientStreamConfig.fromRootYaml(root);

        assertEquals(25, config.defaultMaxFps());
        assertEquals(25, config.maxFpsCap());
    }
}
