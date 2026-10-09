package com.example.iml.orchestrator.integration.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrafficLogTest {

    @Test
    void writesRequestsAndResponsesPerDateAndService(@TempDir Path root) throws Exception {
        TrafficLog.configure(Map.of("integration", Map.of("traffic_log", Map.of("enabled", true, "dir", "log_p"))), root);
        long id = TrafficLog.request("Analis Surface:8001", "POST /inspect-shm", Map.of("camera_id", 3, "blob", new byte[10]));
        TrafficLog.response("Analis Surface:8001", id, "POST /inspect-shm", 200, "{\"status\":\"ok\"}", System.nanoTime());
        TrafficLog.inbound("io_input_monitor", "UDP", "{\"di\":3}");
        TrafficLog.configure(Map.of(), root); // выключение сбрасывает очередь на диск

        Path day;
        try (var dirs = Files.list(root.resolve("log_p"))) {
            day = dirs.findFirst().orElseThrow();
        }
        assertTrue(day.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}"), day.toString());
        String req = Files.readString(day.resolve("analis_surface_8001").resolve("requests.log"));
        String resp = Files.readString(day.resolve("analis_surface_8001").resolve("responses.log"));
        assertTrue(req.contains("#" + id) && req.contains("\"camera_id\":3") && req.contains("<10 bytes>"), req);
        assertTrue(resp.contains("#" + id) && resp.contains("status=200") && resp.contains("duration_ms="), resp);
        assertTrue(Files.readString(day.resolve("io_input_monitor").resolve("responses.log")).contains("{\"di\":3}"));
        assertFalse(TrafficLog.enabled());
        assertEquals(0L, TrafficLog.request("x", "y", null));
    }
}
