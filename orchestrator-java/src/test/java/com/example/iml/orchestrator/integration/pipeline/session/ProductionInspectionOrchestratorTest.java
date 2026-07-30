package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionInspectionOrchestratorTest {

    @Test
    void resolveCycleInputAllowsCaptureWithoutReference() {
        AsyncInspectionCycleInput in = minimalInput(0, new ReferenceSnapshot("bench", Map.of("shm_name", "stale")));
        Map<Integer, ReferenceSnapshot> refs = new HashMap<>();

        AsyncInspectionCycleInput resolved = ProductionInspectionOrchestrator.resolveCycleInput(
                in,
                true,
                refs,
                true
        );

        assertNotNull(resolved);
        assertNull(resolved.activeReference());
    }

    @Test
    void resolveCycleInputBlocksWhenReferenceRequired() {
        AsyncInspectionCycleInput in = minimalInput(1, null);
        Map<Integer, ReferenceSnapshot> refs = new HashMap<>();

        AsyncInspectionCycleInput resolved = ProductionInspectionOrchestrator.resolveCycleInput(
                in,
                true,
                refs,
                false
        );

        assertNull(resolved);
    }

    @Test
    void resolveCycleInputUsesClientReferenceWhenPresent() {
        AsyncInspectionCycleInput in = minimalInput(2, null);
        ReferenceSnapshot ref = new ReferenceSnapshot("client-type", Map.of("shm_name", "ref-shm"));
        Map<Integer, ReferenceSnapshot> refs = Map.of(2, ref);

        AsyncInspectionCycleInput resolved = ProductionInspectionOrchestrator.resolveCycleInput(
                in,
                true,
                refs,
                true
        );

        assertNotNull(resolved);
        assertTrue(resolved.activeReference().isUsable());
        assertTrue("client-type".equals(resolved.productType()));
    }

    private static AsyncInspectionCycleInput minimalInput(int cameraId, ReferenceSnapshot activeReference) {
        var saveCaptures = new IntegrationFeatureConfig.SaveCapturesConfig(false, "testimage", 0.92f);
        return AsyncInspectionCycleInput.of(
                Path.of("."),
                saveCaptures,
                cameraId,
                "bench",
                "v1",
                activeReference,
                0L,
                System.nanoTime(),
                null,
                null,
                java.util.List.of(),
                java.util.List.of(),
                Map.of(),
                Map.of(),
                null,
                null,
                null,
                new java.util.concurrent.atomic.AtomicInteger(),
                new java.util.concurrent.atomic.AtomicInteger(),
                Executors.newSingleThreadExecutor(),
                Executors.newSingleThreadExecutor(),
                Executors.newSingleThreadExecutor(),
                Executors.newSingleThreadExecutor(),
                null,
                0,
                null,
                0L,
                0L,
                null
        );
    }
}
