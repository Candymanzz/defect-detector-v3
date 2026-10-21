package com.example.iml.orchestrator.integration.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceTraceTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();
    @AfterEach void stop() { PerformanceTrace.clearSignal(); PerformanceTrace.shutdown(); }

    private void start() {
        PerformanceTrace.initialize(root, Map.of("integration", Map.of("performance_log",
                Map.of("directory", "timings", "queue_capacity", 8192))));
    }
    private List<JsonNode> records() throws Exception {
        PerformanceTrace.shutdown();
        List<JsonNode> rows = new ArrayList<>();
        try (var files = Files.list(root.resolve("timings"))) {
            for (Path file : files.toList()) for (String line : Files.readAllLines(file)) rows.add(JSON.readTree(line));
        }
        return rows;
    }
    @Test void correlatesTriggerCameraAndPlcWithMonotonicDurations() throws Exception {
        start();
        PerformanceTrace.receivedSignal();
        PerformanceTrace.trigger(41, "test_udp", List.of(0, 1));
        Map<String, Object> header = new HashMap<>();
        PerformanceTrace.attach(header, 41, 1, 123);
        PerformanceTrace.emit("cpp_http_client", "http_request", PerformanceTrace.context(header));
        PerformanceTrace.cycle(41, -1, -1, "plc_output_confirmed", Map.of("group_id", 0));
        List<JsonNode> rows = records();
        JsonNode trigger = rows.stream().filter(r -> r.path("event").asText().equals("trigger_received")).findFirst().orElseThrow();
        JsonNode output = rows.stream().filter(r -> r.path("event").asText().equals("plc_output_confirmed")).findFirst().orElseThrow();
        JsonNode rpc = rows.stream().filter(r -> r.path("event").asText().equals("http_request")).findFirst().orElseThrow();
        assertEquals("UDP packet received by Java", trigger.path("boundary").asText());
        assertEquals(trigger.path("cycle_id"), output.path("cycle_id"));
        assertEquals(trigger.path("cycle_id"), rpc.path("cycle_id"));
        assertEquals(123, rpc.path("frame_id").asInt());
        assertEquals(1, rpc.path("camera_id").asInt());
        assertFalse(rpc.path("request_id").asText().isEmpty());
        assertTrue(output.path("trigger_elapsed_ms").asDouble() >= trigger.path("trigger_elapsed_ms").asDouble());
    }
    @Test void recordsEveryConcurrentRequestWithoutSampling() throws Exception {
        start();
        var producers = Executors.newFixedThreadPool(10);
        try {
            for (int camera = 0; camera < 10; camera++) {
                int id = camera;
                producers.submit(() -> {
                    for (int frame = 0; frame < 100; frame++)
                        PerformanceTrace.cycle(7, id, frame, "sample", Map.of("duration_ms", 1.25));
                });
            }
        } finally { producers.shutdown(); }
        assertTrue(producers.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1000, records().stream().filter(r -> r.path("event").asText().equals("sample")).count());
    }
    @Test void disabledLoggingDoesNotCreateDirectory() {
        PerformanceTrace.initialize(root, Map.of("integration", Map.of("performance_log", Map.of("enabled", false))));
        PerformanceTrace.trigger(1, "test", List.of(0));
        PerformanceTrace.emit("test", "event", Map.of());
        assertFalse(Files.exists(root.resolve("cppAnalisSurface/logs")));
    }
    @Test void restartAppendsWithoutRemovingPreviousRecords() throws Exception {
        start();
        PerformanceTrace.emit("test", "before_restart", Map.of());
        PerformanceTrace.shutdown();
        start();
        PerformanceTrace.emit("test", "after_restart", Map.of());
        List<JsonNode> rows = records();
        assertEquals(1, rows.stream().filter(r -> r.path("event").asText().equals("before_restart")).count());
        assertEquals(1, rows.stream().filter(r -> r.path("event").asText().equals("after_restart")).count());
    }
    @Test void contextNeverCopiesImagesOrRequestBodies() {
        Map<String, Object> fields = PerformanceTrace.context(Map.of("camera_id", 3, "frame_id", 7,
                "payload", new byte[1024], "image", "private", "settings", Map.of("x", 1)));
        assertEquals(Map.of("camera_id", 3, "frame_id", 7), fields);
    }
}
