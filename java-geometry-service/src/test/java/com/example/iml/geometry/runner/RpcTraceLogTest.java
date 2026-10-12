package com.example.iml.geometry.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcTraceLogTest {
    @TempDir
    Path tempDir;

    @Test
    void writesPairedRequestAndErrorResponseWithElapsedTime() throws Exception {
        Path file = tempDir.resolve("geometry/rpc-1.jsonl");
        RpcTraceLog trace = new RpcTraceLog(file);
        RpcTraceLog.Call call = trace.request(1, Map.of("op", "inspect_shm", "camera_id", 3), 0);
        trace.response(call, 3, Map.of("status", "ERROR", "error", "missing frame"), 0);

        var lines = Files.readAllLines(file);
        var mapper = new ObjectMapper();
        var request = mapper.readTree(lines.get(0));
        var response = mapper.readTree(lines.get(1));
        assertEquals(2, lines.size());
        assertEquals("inspect_shm", request.get("op").asText());
        assertEquals(3, request.get("camera_id").asInt());
        assertEquals(request.get("request_id").asText(), response.get("request_id").asText());
        assertEquals("ERROR", response.get("status").asText());
        assertTrue(response.get("duration_ms").asDouble() >= 0.0);
    }
}
