package com.example.iml.orchestrator.integration.lighting;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void disabledClientReportsThatBrightnessWasNotAppliedToHardware() {
        LightTriggerClient client = new LightTriggerClient(LightServersConfig.disabled());

        LightBrightnessApplyResult result = client.applyBrightnessUpdate(LightBrightnessUpdate.globalOnly(42));

        assertTrue(result.hasHardwareErrors());
        assertEquals(List.of("light_servers disabled"), result.hardwareErrors());
    }

    @Test
    void captureGateDefersHardwarePushUntilFlush() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> ls = new LinkedHashMap<>();
        ls.put("enabled", false);
        ls.put("base_url", "http://127.0.0.1:5080");
        ls.put("cameras", List.of(
                Map.of("camera_id", 1, "mode", "pair", "brightness_percent", 80)
        ));
        root.put("light_servers", ls);

        LightTriggerClient client = new LightTriggerClient(LightServersConfig.fromRootYaml(root));
        AtomicBoolean captureActive = new AtomicBoolean(true);
        AtomicInteger latchCount = new AtomicInteger();
        client.setCaptureLightingActive(captureActive::get);
        client.setAfterBrightnessApplied(latchCount::incrementAndGet);

        LightBrightnessApplyResult deferred = client.applyBrightnessUpdate(
                new LightBrightnessUpdate(null, Map.of("camera-1", 33))
        );

        assertEquals(33, client.brightnessPercent("camera-1"));
        assertTrue(client.hasDeferredHardwareBrightness());
        assertEquals(0, latchCount.get());
        assertFalse(deferred.hasHardwareErrors());

        captureActive.set(false);
        LightBrightnessApplyResult flushed = client.flushDeferredBrightness();
        assertFalse(client.hasDeferredHardwareBrightness());
        // disabled → flush reports disabled, still no latch (by design after capture Off)
        assertTrue(flushed.hasHardwareErrors());
        assertEquals(0, latchCount.get());
    }
    @Test
    void configuredRetryCountControlsFailedHardwareRequests() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/com/light", exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] error = "{\"success\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, error.length);
            try (var output = exchange.getResponseBody()) { output.write(error); }
        });
        server.start();
        try {
            var root = Map.<String, Object>of("light_servers", Map.of("enabled", true,
                    "base_url", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "runtime", Map.of("command_attempts", 3, "retry_delay_ms", 0)));
            var client = new LightTriggerClient(LightServersConfig.fromRootYaml(root), null, LightRuntimeConfig.parse(root));
            assertFalse(client.lightOn(42, 1, "test"));
            assertEquals(3, requests.get());
        } finally { server.stop(0); }
    }

}
