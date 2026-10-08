package com.example.iml.orchestrator.integration.lighting.nativeio;

import com.example.iml.orchestrator.integration.lighting.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeLightBankTest {
    static Map<String, Object> root() {
        return Map.of("light_servers", Map.of("enabled", true, "sdk", Map.of("reconnect_ms", 25, "command_timeout_ms", 1000)),
                "light_hardware", Map.of("devices", List.of(
                        Map.of("id", "eth", "type", "ethernet", "ip", "169.254.1.1", "channels", 2),
                        Map.of("id", "com", "type", "com", "com_port", "COM_Port#ttyUSB0", "channels", 1)),
                        "camera_routes", List.of(Map.of("camera_number", 1, "device_id", "eth", "channels", List.of(1,2)),
                                Map.of("camera_number", 2, "device_id", "com", "channels", List.of(1)))));
    }
    static class Fake implements LightDevice {
        final List<String> writes = new CopyOnWriteArrayList<>();
        volatile String worker;
        volatile boolean closed;
        volatile boolean fail;
        final AtomicInteger opens = new AtomicInteger();
        CyclicBarrier barrier;
        boolean firstOn;
        public void open() { worker = Thread.currentThread().getName(); if (fail) throw new IllegalStateException("unplugged"); opens.incrementAndGet(); closed = false; firstOn = true; }
        public void brightness(int ch, int value) { assertEquals(worker, Thread.currentThread().getName()); writes.add("B" + ch + ":" + value); }
        public void source(int ch, boolean on) {
            assertEquals(worker, Thread.currentThread().getName());
            if (on && firstOn && barrier != null) { firstOn = false; try { barrier.await(500, TimeUnit.MILLISECONDS); } catch (Exception e) { throw new IllegalStateException(e); } }
            if (fail) throw new IllegalStateException("unplugged"); writes.add("S" + ch + ":" + on);
        }
        public void checkConnection() { if (fail) throw new IllegalStateException("unplugged"); }
        public void close() { closed = true; }
    }
    static void ready(NativeLightBank bank) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!bank.ready() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(bank.ready(), bank.status().toString());
    }
    @Test void brightnessUsesCameraRoutesAndNeverTurnsChannelsOn() throws Exception {
        Map<String, Fake> fakes = Map.of("eth", new Fake(), "com", new Fake());
        try (var bank = new NativeLightBank(NativeLightConfig.parse(root()), d -> fakes.get(d.id()))) {
            ready(bank); fakes.values().forEach(f -> f.writes.clear());
            bank.cameraBrightness(1, 100, 200); bank.cameraBrightness(2, 50);
            assertEquals(List.of("B1:100", "B2:200"), fakes.get("eth").writes);
            assertEquals(List.of("B1:50"), fakes.get("com").writes);
            assertThrows(IllegalArgumentException.class, () -> bank.cameraBrightness(99, 50));
            assertThrows(IllegalArgumentException.class, () -> bank.cameraBrightness(2, 256));
        }
        assertTrue(fakes.values().stream().allMatch(f -> f.closed));
        assertTrue(fakes.get("eth").writes.contains("S2:false"));
    }
    @Test void bankRunsDevicesInParallelAndShutdownTurnsEveryChannelOff() throws Exception {
        Fake eth = new Fake(), com = new Fake(); var barrier = new CyclicBarrier(2); eth.barrier = com.barrier = barrier;
        try (var bank = new NativeLightBank(NativeLightConfig.parse(root()), d -> d.id().equals("eth") ? eth : com)) {
            ready(bank); eth.writes.clear(); com.writes.clear(); bank.bank(true);
            assertNotEquals(eth.worker, com.worker);
            assertEquals(List.of("S1:true", "S2:true"), eth.writes);
            assertEquals(List.of("S1:true"), com.writes);
            bank.bank(false);
            assertEquals(List.of("S1:true", "S2:true", "S1:false", "S2:false"), eth.writes);
        }
        assertTrue(eth.closed && com.closed);
    }
    @Test void reconnectRestoresBrightnessAndStartsDarkWithoutReplayingOn() throws Exception {
        Fake eth = new Fake(), com = new Fake();
        try (var bank = new NativeLightBank(NativeLightConfig.parse(root()), d -> d.id().equals("eth") ? eth : com)) {
            ready(bank); bank.cameraBrightness(1, 70, 80); bank.bank(true);
            eth.fail = true;
            assertThrows(IllegalStateException.class, () -> bank.bank(true));
            assertFalse(bank.ready());
            eth.writes.clear(); eth.fail = false; ready(bank);
            assertTrue(eth.opens.get() >= 2);
            assertTrue(eth.writes.containsAll(List.of("S1:false", "S2:false", "B1:70", "B2:80")));
            assertFalse(eth.writes.contains("S1:true"));
        }
    }
    @Test void clientAndApiUseNativeCommandsWithoutAnHttpLightServer() throws Exception {
        Fake eth = new Fake(), com = new Fake();
        try (var bank = new NativeLightBank(NativeLightConfig.parse(root()), d -> d.id().equals("eth") ? eth : com)) {
            ready(bank);
            var cfg = new LightServersConfig(true, true, 1000, 0, 0, 50, 80, true,
                    "http://127.0.0.1:1", "/api/com/light", "/api/com/light", "/api/camera-flash/pair", "/api/camera-flash/single", "/api/camera-flash/bank",
                    List.of(new LightServersConfig.CameraFlashSpec(0, LightServersConfig.FlashMode.PAIR, 50, 50, 50),
                            new LightServersConfig.CameraFlashSpec(1, LightServersConfig.FlashMode.SINGLE, 50, 50, 50)));
            var client = new LightTriggerClient(cfg, bank); var api = new LightUpstreamClient(cfg, client);
            eth.writes.clear(); com.writes.clear(); client.startupEngage();
            assertFalse(eth.writes.contains("S1:true"));
            assertTrue(client.bankAllOn("test"));
            assertTrue(eth.writes.contains("S1:true") && com.writes.contains("S1:true"));
            assertTrue(api.get("/api/camera-flash/bank").body().contains("native-mvs"));
            assertEquals(200, api.post("/api/camera-flash/bank", "{\"state\":\"off\"}".getBytes()).statusCode());
            assertEquals(400, api.post("/api/camera-flash/single", "{\"cameraNumber\":99,\"power\":50}".getBytes()).statusCode());
            client.shutdown(); assertTrue(eth.closed && com.closed);
        }
    }
    @Test void targetedLegacyApiNeverSwitchesUnrelatedDevices() throws Exception {
        Fake eth = new Fake(), com = new Fake();
        try (var bank = new NativeLightBank(NativeLightConfig.parse(root()), d -> d.id().equals("eth") ? eth : com)) {
            ready(bank); eth.writes.clear(); com.writes.clear();
            bank.request("POST", "/api/light", Map.of("deviceIndex", 0, "channels", List.of(2), "brightness", List.of(120), "lightControllerSource", "On"));
            assertEquals(List.of("B2:120", "S2:true"), eth.writes); assertTrue(com.writes.isEmpty());
        }
    }
    @Test void bankAcceptsLegacyBrightnessAndRejectsInvalidRequestsBeforeHardwareWrites() throws Exception {
        Fake eth = new Fake(), com = new Fake();
        try (var bank = new NativeLightBank(NativeLightConfig.parse(root()), d -> d.id().equals("eth") ? eth : com)) {
            ready(bank); eth.writes.clear(); com.writes.clear();
            bank.request("POST", "/api/com/light", Map.of("state", "on", "brightness", "50"));
            assertEquals(List.of("S1:false", "B1:128", "S1:true"), com.writes);
            assertTrue(eth.writes.isEmpty());
            bank.request("POST", "/api/camera-flash/bank", Map.of("state", "on", "brightnessByIp", Map.of("169.254.1.1", List.of(70, 80))));
            assertTrue(eth.writes.containsAll(List.of("B1:70", "B2:80", "S1:true", "S2:true")));
            eth.writes.clear(); com.writes.clear();
            assertThrows(IllegalArgumentException.class, () -> bank.request("POST", "/api/camera-flash/bank",
                    Map.of("state", "on", "brightnessByIp", Map.of("169.254.1.1", List.of(500, 80)))));
            assertThrows(IllegalArgumentException.class, () -> bank.request("POST", "/api/com/light", Map.of("state", "on", "brightness", "101")));
            assertTrue(eth.writes.isEmpty() && com.writes.isEmpty());
        }
    }

    @Test void initialBrightnessAndRefreshPolicyComeFromConfiguration() throws Exception {
        var root = new HashMap<>(root());
        root.put("light_servers", Map.of("sdk", Map.of("initial_brightness", 17, "health_check_ms", 25, "refresh_before_on", false)));
        Fake eth = new Fake(), com = new Fake();
        try (var bank = new NativeLightBank(NativeLightConfig.parse(root), d -> d.id().equals("eth") ? eth : com)) {
            ready(bank);
            assertTrue(eth.writes.containsAll(List.of("B1:17", "B2:17")));
            eth.writes.clear();
            bank.request("POST", "/api/camera-flash/bank", Map.of("state", "on", "brightnessByIp", Map.of("169.254.1.1", List.of(50, 60))));
            assertEquals(List.of("B1:50", "B2:60", "S1:true", "S2:true"), eth.writes);
        }
    }

}
