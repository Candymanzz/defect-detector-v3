package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.clientapi.CppAnalisSurfaceHttpSupervisor;
import com.example.iml.orchestrator.integration.pipeline.decision.DefaultInspectionDecisionAggregator;
import com.example.iml.orchestrator.integration.ui.FrameArchiveConfig;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CppLearningIntegrationTest {
    @TempDir Path dir;

    private InspectionDecision decodeAndDecide() throws Exception {
        var supervisor = new CppAnalisSurfaceHttpSupervisor("test", "http://127.0.0.1:1", 100);
        var decode = CppAnalisSurfaceHttpSupervisor.class.getDeclaredMethod("inspectResponse", Map.class, Map.class);
        decode.setAccessible(true);
        var response = (BinaryProtocol.Message) decode.invoke(supervisor, Map.of("product_type", "bucket#cam=1"), Map.of(
                "status", "ГОДЕН", "anomaly_score", 0.05, "raw_anomaly_score", 0.8,
                "learning_engine", "robust-v2", "learning_robust_enabled", true, "learning_ms", 3.2,
                "learning_diagnostics", List.of(Map.of("id", "normal-1", "method", "shadow", "shift", List.of(20, 8), "removed_pixels", 200)),
                "learned_normal_matches_count", 1, "matched_accepted_case_ids", List.of("normal-1")));
        return new DefaultInspectionDecisionAggregator(null).decide(1,
                new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, Map.of("frame_id", 42L), new byte[0]),
                response, new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, Map.of("status", "SKIPPED"), new byte[0]));
    }

    @Test void keepsLearningDiagnosticsBoundToTheFrameDecision() throws Exception {
        var decision = decodeAndDecide();
        assertTrue(decision.overallPass());
        assertEquals(42, decision.frameId());
        assertEquals("robust-v2", decision.learning().get("learning_engine"));
        assertEquals(0.8, decision.learning().get("raw_anomaly_score"));
        assertEquals(1, ((List<?>)decision.learning().get("learning_diagnostics")).size());
        assertTrue(LearningDiagnostics.fromResponse(null).isEmpty());
    }

    @Test void retainsDiagnosticsAcrossArchiveRestart() throws Exception {
        var decision = decodeAndDecide();
        var config = new FrameArchiveConfig(true, dir.resolve("archive"), 10, 10);
        var image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var source = dir.resolve("frame.jpg");
        javax.imageio.ImageIO.write(image, "jpg", source.toFile());
        try (var archive = FrameArchiveService.open(config)) {
            assertTrue(archive.saveImmediately(new FrameArchiveService.SaveRequest(1, 42, 1, "bucket", "cpp", decision, source, null, 0, 0)));
        }
        try (var archive = FrameArchiveService.open(config)) {
            assertEquals(decision.learning(), archive.listHistory(1).get(0).learning());
        }
        var metadata = new ObjectMapper().readTree(Files.readString(dir.resolve("archive/camera_1/f_0000042/result.json")));
        assertEquals("normal-1", metadata.path("learning").path("learning_diagnostics").get(0).path("id").asText());
    }
}
