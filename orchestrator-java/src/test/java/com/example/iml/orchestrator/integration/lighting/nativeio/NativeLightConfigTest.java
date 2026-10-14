package com.example.iml.orchestrator.integration.lighting.nativeio;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeLightConfigTest {
    @Test void platformPortsAndChannelMappingHaveNoWindowsAssumptions() {
        assertEquals("COM_Port#ttyS4", NativeLightConfig.sdkPort("/dev/ttyS4"));
        assertEquals("COM_Port#ttyUSB0", NativeLightConfig.sdkPort("COM_Port#ttyUSB0"));
        assertEquals("COM_Port#COM3", NativeLightConfig.sdkPort("com3"));
        var config = NativeLightConfig.parse(NativeLightBankTest.root());
        assertEquals(List.of(1, 2), config.devices().get(0).channels());
        assertEquals("com", config.routes().get(2).deviceId());
        assertEquals(96, new JnaLightDevice.IntValue().size());
        assertEquals(28, new JnaLightDevice.FloatValue().size());
    }
    @Test void unknownDuplicateOrUnconfiguredRoutesFailBeforeOpeningSdk() {
        var root = new HashMap<>(NativeLightBankTest.root());
        root.put("light_hardware", Map.of("devices", List.of(Map.of("id", "eth", "type", "ethernet", "ip", "1.2.3.4", "channels", 2)),
                "camera_routes", List.of(Map.of("camera_number", 1, "device_id", "eth", "channels", List.of(3)))));
        assertThrows(IllegalArgumentException.class, () -> NativeLightConfig.parse(root));
        assertThrows(IllegalArgumentException.class, () -> NativeLightConfig.parse(Map.of()));
        assertTrue(NativeLightConfig.nativeBackend(Map.of()));
        assertFalse(NativeLightConfig.nativeBackend(Map.of("light_servers", Map.of("backend", "http"))));
    }
    @Test void cameraModeComesFromRouteWithoutFixedCameraIds() {
        var root = new HashMap<String, Object>();
        root.put("light_servers", Map.of("enabled", true, "cameras", List.of(
                Map.of("camera_id", 0), Map.of("camera_id", 12))));
        root.put("light_hardware", Map.of("camera_routes", List.of(
                Map.of("camera_number", 1, "channels", List.of(1)),
                Map.of("camera_number", 13, "channels", List.of(2, 3)))));
        var config = com.example.iml.orchestrator.integration.lighting.LightServersConfig.fromRootYaml(root);
        assertEquals(2, config.cameras().size());
        assertEquals(com.example.iml.orchestrator.integration.lighting.LightServersConfig.FlashMode.SINGLE, config.cameras().get(0).mode());
        assertEquals(com.example.iml.orchestrator.integration.lighting.LightServersConfig.FlashMode.PAIR, config.cameras().get(1).mode());
        assertEquals(12, config.cameras().get(1).cameraId());
    }

    @Test void deviceProfileAndRuntimeSettingsOverrideDefaults() {
        var root = new HashMap<>(NativeLightBankTest.root());
        root.put("light_servers", Map.of("sdk", Map.of("health_check_ms", 123, "worker_poll_ms", 15,
                "initial_brightness", 17, "use_broadcast", false, "refresh_before_on", false,
                "source_on", "Enabled", "source_off", "Disabled")));
        root.put("light_hardware", Map.of("devices", List.of(Map.of("id", "custom", "type", "ethernet", "ip", "1.2.3.4", "channels", 2,
                "model_prefixes", List.of("MV-NEW"), "brightness_min", 10, "brightness_max", 110))));
        var config = NativeLightConfig.parse(root);
        var device = config.devices().get(0);
        assertEquals(List.of("MV-NEW"), device.modelPrefixes());
        assertEquals("Enabled", device.sourceOn());
        assertEquals("Disabled", device.sourceOff());
        assertEquals(10, device.sdkBrightness(0));
        assertEquals(110, device.sdkBrightness(255));
        assertEquals(60, device.sdkBrightness(128));
        assertEquals(123, config.runtime().healthCheckMs());
        assertEquals(15, config.runtime().workerPollMs());
        assertEquals(17, config.runtime().initialBrightness());
        assertFalse(config.runtime().useBroadcast());
        assertFalse(config.runtime().refreshBeforeOn());
    }

    @Test @SuppressWarnings("unchecked") void shippedYamlImportsPreserveSourceSymbolsAndHardwareRoutes() throws Exception {
        java.nio.file.Path folder = java.nio.file.Files.isDirectory(java.nio.file.Path.of("config.exemple"))
                ? java.nio.file.Path.of("config.exemple") : java.nio.file.Path.of("../config.exemple");
        var root = new com.example.iml.orchestrator.config.YamlFileConfigLoader().load(folder.resolve("config.yaml"));
        var hardware = (Map<String, Object>)root.get("light_hardware");
        var devices = (List<Map<String, Object>>)hardware.get("devices");
        devices.get(0).put("com_port_linux", "COM_Port#testFlash");
        var config = NativeLightConfig.parse(root);
        assertEquals("On", config.devices().get(0).sourceOn());
        assertEquals("Off", config.devices().get(0).sourceOff());
        assertEquals(10, config.routes().size());
        var runtime = com.example.iml.orchestrator.integration.lighting.LightRuntimeConfig.parse(root);
        assertEquals(10, runtime.commandAttempts());
        assertEquals(200, runtime.retryDelayMs());
    }

}
