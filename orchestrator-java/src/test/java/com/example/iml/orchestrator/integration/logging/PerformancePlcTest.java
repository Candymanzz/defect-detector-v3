package com.example.iml.orchestrator.integration.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.plc.*;
import com.example.iml.orchestrator.integration.plc.fins.OmronFinsClient;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PerformancePlcTest {
    @TempDir Path root;
    @AfterEach void cleanup() { PerformanceTrace.shutdown(); }

    private List<JsonNode> exercise(boolean rejectWrites) throws Exception {
        PerformanceTrace.initialize(root, Map.of("integration", Map.of("performance_log", Map.of("directory", "timings"))));
        PerformanceTrace.trigger(91, "test", List.of(0, 5));
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Thread plc = new Thread(() -> {
                while (!socket.isClosed()) try {
                    byte[] data = new byte[256];
                    DatagramPacket request = new DatagramPacket(data, data.length);
                    socket.receive(request);
                    byte[] response = Arrays.copyOf(data, 14);
                    response[12] = rejectWrites ? (byte) 0x21 : 0;
                    response[13] = rejectWrites ? (byte) 0x05 : 0;
                    socket.send(new DatagramPacket(response, response.length, request.getAddress(), request.getPort()));
                } catch (Exception error) { if (!socket.isClosed()) throw new RuntimeException(error); }
            }, "fake-plc");
            plc.setDaemon(true); plc.start();
            PlcRegisterMap map = new PlcRegisterMap(Map.of(
                    "ready", new PlcSignalDefinition("ready", "", PlcMemoryArea.W, new PlcAddress(0, 4), null),
                    "reject0", new PlcSignalDefinition("reject0", "", PlcMemoryArea.W, new PlcAddress(0, 5), 0),
                    "reject1", new PlcSignalDefinition("reject1", "", PlcMemoryArea.W, new PlcAddress(0, 6), 1)));
            PlcFinsConfig config = new PlcFinsConfig(true, "127.0.0.1", socket.getLocalPort(), 1, 2,
                    500, 30, 8, root.resolve("unused.yaml"), "ready", "unused");
            OmronFinsClient client = new OmronFinsClient(config.host(), config.port(), 1, 2, 500);
            try (PlcFinsPublisher publisher = new PlcFinsPublisher(LogManager.getLogger(getClass()), config, map, client)) {
                publisher.publishBucket(new BucketFanOutResult(0, 91, false, List.of(0), Map.of()), true, false);
                publisher.publishBucket(new BucketFanOutResult(1, 91, true, List.of(5), Map.of()), true, false);
            }
            socket.close(); plc.join(1000);
        }
        PerformanceTrace.shutdown();
        List<JsonNode> rows = new ArrayList<>();
        try (var files = Files.list(root.resolve("timings"))) {
            for (Path file : files.toList()) for (String line : Files.readAllLines(file)) rows.add(new ObjectMapper().readTree(line));
        }
        return rows;
    }
    @Test void measuresQueuePulseAndActualAcknowledgementWithoutTreatingResetAsVerdict() throws Exception {
        List<JsonNode> rows = exercise(false);
        List<JsonNode> outputs = rows.stream().filter(r -> r.path("event").asText().equals("plc_output_confirmed")).toList();
        assertEquals(2, outputs.size());
        for (JsonNode row : outputs) {
            assertEquals(91, row.path("trigger_sequence").asInt());
            assertTrue(row.path("success").asBoolean());
            assertTrue(row.path("trigger_elapsed_ms").asDouble() >= row.path("write_ms").asDouble());
            assertFalse(row.path("pulse_reset").asBoolean());
        }
        assertTrue(rows.stream().anyMatch(r -> r.path("event").asText().equals("plc_pulse_hold")));
        assertTrue(rows.stream().anyMatch(r -> r.path("pulse_reset").asBoolean()));
    }
    @Test void rejectedFinsResponseIsNeverLoggedAsConfirmedOutput() throws Exception {
        List<JsonNode> rows = exercise(true);
        assertFalse(rows.stream().anyMatch(r -> r.path("event").asText().equals("plc_output_confirmed")));
        assertEquals(2, rows.stream().filter(r -> r.path("event").asText().equals("plc_output_failed")).count());
    }
}
