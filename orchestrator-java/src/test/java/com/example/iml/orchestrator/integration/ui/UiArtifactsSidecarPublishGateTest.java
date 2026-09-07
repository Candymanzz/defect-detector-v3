package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPositioningExecutor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiArtifactsSidecarPublishGateTest {

    private static final ReferenceSnapshot REFERENCE = new ReferenceSnapshot("bucket", Map.of("shm_name", "/ref"));

    @Test
    void allowsRawWhenNoReference() {
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("positioning_ms", 12);
        cap.put(InspectPositioningExecutor.HEADER_ALIGNED, false);
        assertTrue(UiArtifactsSidecar.shouldPublishUiFrameJpeg(null, cap));
    }

    @Test
    void allowsRawWhenPositioningDidNotRun() {
        Map<String, Object> cap = Map.of("frame_id", 7);
        assertTrue(UiArtifactsSidecar.shouldPublishUiFrameJpeg(REFERENCE, cap));
    }

    @Test
    void blocksUntilAlignedWhenPositioningAttempted() {
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("positioning_ms", 40);
        cap.put("positioning_status", "FAIL");
        cap.put(InspectPositioningExecutor.HEADER_ALIGNED, false);
        assertFalse(UiArtifactsSidecar.shouldPublishUiFrameJpeg(REFERENCE, cap));
    }

    @Test
    void publishesAfterAlignedWrite() {
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("positioning_ms", 40);
        cap.put("positioning_status", "PASS");
        cap.put(InspectPositioningExecutor.HEADER_ALIGNED, true);
        assertTrue(UiArtifactsSidecar.shouldPublishUiFrameJpeg(REFERENCE, cap));
    }
}
