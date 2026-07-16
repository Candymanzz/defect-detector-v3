package com.example.iml.orchestrator.integration.lighting;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightServersConfigTest {

    @Test
    void resolvesThreeUrlTypesFromBaseUrl() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> ls = new LinkedHashMap<>();
        ls.put("enabled", true);
        ls.put("base_url", "http://127.0.0.1:5080");
        ls.put("urls", Map.of(
                "on", "/api/com/light",
                "off", "/api/com/light",
                "brightness_pair", "/api/camera-flash/pair",
                "brightness_single", "/api/camera-flash/single"
        ));
        root.put("light_servers", ls);
        root.put("cameras", List.of(
                Map.of("id", 0, "enabled", true),
                Map.of("id", 3, "enabled", true),
                Map.of("id", 8, "enabled", true),
                Map.of("id", 9, "enabled", true)
        ));

        LightServersConfig cfg = LightServersConfig.fromRootYaml(root);

        assertEquals("http://127.0.0.1:5080/api/com/light", cfg.onUrl());
        assertEquals("http://127.0.0.1:5080/api/com/light", cfg.offUrl());
        assertEquals("http://127.0.0.1:5080/api/camera-flash/pair", cfg.brightnessPairUrl());
        assertEquals("http://127.0.0.1:5080/api/camera-flash/single", cfg.brightnessSingleUrl());
        // id 8–9 без вспышек — в light_servers.cameras не попадают.
        assertEquals(2, cfg.cameras().size());
        assertEquals(LightServersConfig.FlashMode.PAIR, cfg.camera(0).mode());
        assertEquals(LightServersConfig.FlashMode.PAIR, cfg.camera(3).mode());
        assertEquals(null, cfg.camera(8));
        assertEquals(null, cfg.camera(9));
    }

    @Test
    void camerasWithoutFlashHardwareAreSkipped() {
        assertTrue(LightServersConfig.hasFlashHardware(0));
        assertTrue(LightServersConfig.hasFlashHardware(7));
        assertTrue(!LightServersConfig.hasFlashHardware(8));
        assertTrue(!LightServersConfig.hasFlashHardware(9));
    }

    @Test
    void migratesLegacyEndpointsToCameras() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> ls = new LinkedHashMap<>();
        ls.put("enabled", true);
        ls.put("base_url", "http://127.0.0.1:5080");
        ls.put("endpoints", List.of(
                Map.of(
                        "id", "light-com3-lan4",
                        "enabled", true,
                        "brightness_percent", 80,
                        "camera_ids", List.of(2)
                )
        ));
        root.put("light_servers", ls);

        LightServersConfig cfg = LightServersConfig.fromRootYaml(root);

        assertEquals(1, cfg.cameras().size());
        assertEquals(2, cfg.camera(2).cameraId());
        assertEquals(80, cfg.camera(2).brightnessPercent());
    }

    @Test
    void endpointIdUsesCameraPrefix() {
        LightServersConfig.CameraFlashSpec spec = new LightServersConfig.CameraFlashSpec(
                3, LightServersConfig.FlashMode.PAIR, 50, 50, 50
        );
        assertEquals("camera-3", spec.endpointId());
        assertEquals(4, spec.cameraNumber());
        assertTrue(spec.leftPower255() > 0);
    }
}
