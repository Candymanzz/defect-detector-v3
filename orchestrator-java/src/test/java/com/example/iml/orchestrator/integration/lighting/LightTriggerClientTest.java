package com.example.iml.orchestrator.integration.lighting;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightTriggerClientTest {

    @Test
    void globalBrightnessUpdateChangesDefaultPercent() {
        LightTriggerClient client = new LightTriggerClient(LightServersConfig.disabled());

        client.setBrightnessPercent(42);

        assertEquals(42, client.brightnessPercent());
    }

    @Test
    void perEndpointBrightnessIsTrackedInMemory() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> ls = new LinkedHashMap<>();
        ls.put("enabled", false);
        ls.put("base_url", "http://127.0.0.1:5080");
        ls.put("cameras", List.of(
                Map.of("camera_id", 3, "mode", "pair", "brightness_percent", 80)
        ));
        root.put("light_servers", ls);

        LightTriggerClient client = new LightTriggerClient(LightServersConfig.fromRootYaml(root));
        client.setBrightnessPercent("camera-3", 55);

        assertEquals(55, client.brightnessPercent("camera-3"));
        assertEquals(55, client.brightnessByEndpoint().get("camera-3"));
    }
}
