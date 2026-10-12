package com.example.iml.orchestrator.integration.logging;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketGroup;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionConfig;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerVerdictLogTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path session;

    @Test
    void recordsOneLogicalVerdictAfterBothGroupsAndMeasuresFromTrigger() throws Exception {
        TriggerVerdictLog trace = new TriggerVerdictLog(session);
        try (InspectionTriggerBus bus = new InspectionTriggerBus(List.of(0, 1), 0, trace);
             BucketInspectionAggregator aggregator = new BucketInspectionAggregator(
                     LogManager.getLogger(TriggerVerdictLogTest.class),
                     new BucketInspectionConfig(true,
                             List.of(new BucketGroup(0, List.of(0)), new BucketGroup(1, List.of(1))),
                             1000L, 1000L))) {
            aggregator.setTriggerVerdictLog(trace);
            long receivedNanos = System.nanoTime() - 650_000_000L;
            int dispatched = bus.dispatchLineBroadcastWithoutPrefire(
                    "io_input", null, Instant.now().minusMillis(650), receivedNanos);
            assertEquals(2, dispatched);
            long sequence = bus.currentSequence();
            List<BucketFanOutResult> published = new ArrayList<>();

            aggregator.recordFrameResult(sequence, 0, decision(0, true), published::add);
            assertEquals(0, published.size());
            aggregator.recordFrameResult(sequence, 1, decision(1, false), published::add);
            assertEquals(2, published.size());

            List<JsonNode> rows = rows();
            assertEquals(4, rows.size());
            assertEquals("trigger_received", rows.get(0).path("event").asText());
            assertEquals("io_input", rows.get(0).path("source").asText());
            assertEquals("verdict_ready", rows.get(3).path("event").asText());
            assertEquals(sequence, rows.get(3).path("trigger_sequence").asLong());
            assertEquals("io_input", rows.get(3).path("source").asText());
            assertEquals("REJECT", rows.get(3).path("verdict").asText());
            assertEquals("НЕ ГОДЕН", rows.get(3).path("verdict_ru").asText());
            assertTrue(rows.get(3).path("duration_ms").asDouble() >= 600.0);
            assertFalse(rows.get(3).path("start_missing").asBoolean());
            assertEquals(2, rows.get(3).path("groups").size());
        }
    }

    @Test
    void distinguishesCaptureOnlyAndSkippedTrigger() throws Exception {
        TriggerVerdictLog trace = new TriggerVerdictLog(session);
        trace.triggerReceived(1L, Instant.now(), System.nanoTime(), "io_input", List.of(0));
        trace.verdictReady(1L, List.of(new BucketFanOutResult(0, 1L, true,
                List.of(0), Map.of(0, InspectionDecision.captureOnly(0, 10L)))));
        trace.triggerReceived(2L, Instant.now(), System.nanoTime(), "io_input", List.of(0));
        trace.triggerSkipped(2L, "services_unhealthy");
        trace.triggerReceived(3L, Instant.now(), System.nanoTime(), "io_input", List.of(0));
        trace.verdictReady(3L, List.of(new BucketFanOutResult(0, 3L, true, List.of(0), Map.of())));
        trace.triggerReceived(4L, Instant.now(), System.nanoTime(), "io_input", List.of(0));
        trace.verdictReady(4L, List.of(new BucketFanOutResult(0, 4L, false, List.of(0), Map.of())));

        List<JsonNode> rows = rows();
        assertEquals("CAPTURE_ONLY", rows.get(1).path("verdict").asText());
        assertTrue(rows.get(1).path("capture_only").asBoolean());
        assertEquals("trigger_skipped", rows.get(3).path("event").asText());
        assertEquals("services_unhealthy", rows.get(3).path("reason").asText());
        assertEquals("NO_INSPECTION", rows.get(5).path("verdict").asText());
        assertEquals("REJECT", rows.get(7).path("verdict").asText());
    }

    private List<JsonNode> rows() throws IOException {
        List<JsonNode> rows = new ArrayList<>();
        for (String line : Files.readAllLines(session.resolve("trigger-verdict.jsonl"))) {
            rows.add(MAPPER.readTree(line));
        }
        return rows;
    }

    private static InspectionDecision decision(int cameraId, boolean pass) {
        return InspectionDecision.simple(cameraId, 100L + cameraId, pass,
                pass ? "PASS" : "REJECT", 0.0, "OK", "OK");
    }
}
