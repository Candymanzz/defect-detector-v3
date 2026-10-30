package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPositioningExecutor;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiArtifactsSidecarPublishGateTest {

    private static final ReferenceSnapshot REFERENCE = new ReferenceSnapshot("bucket", Map.of("shm_name", "/ref"));

    @Test
    void heatmapUsesDetachedOriginalSettingsAndRejectsIncompleteSnapshot() {
        Map<String, Object> seam = new LinkedHashMap<>(Map.of("enabled", false,
                "p0", java.util.List.of(0.1, 0.2), "p1", java.util.List.of(0.8, 0.9),
                "band", 30, "tol_mid", 1, "tol_edge", 5, "edge_frac", 0.12));
        Map<String, Object> original = new LinkedHashMap<>(Map.of("sensitivity", 0.8, "threshold", 0.25,
                "shift_tolerance_x", 6, "shift_tolerance_y", 5, "max_alignment_shift_x", 24,
                "max_alignment_shift_y", 20, "min_blob_area", 1,
                "inspect_scale", 0.7, "seam", seam));
        Map<String, Object> request = new LinkedHashMap<>(Map.of("settings", Map.of("sensitivity", 0.1),
                "seam_line_norm", java.util.List.of()));
        assertTrue(UiArtifactsSidecar.applyOriginalHeatmapSettings(request, Map.of("settings", original)));
        assertEquals(original, request.get("settings"));
        assertFalse(request.containsKey("seam_line_norm"));
        original.put("sensitivity", 0.2);
        seam.put("band", 100);
        Map<?, ?> snapshot = (Map<?, ?>) request.get("settings");
        assertEquals(0.8, snapshot.get("sensitivity"));
        assertEquals(30, ((Map<?, ?>) snapshot.get("seam")).get("band"));
        assertFalse(UiArtifactsSidecar.applyOriginalHeatmapSettings(new LinkedHashMap<>(), Map.of()));
        original.remove("inspect_scale");
        assertFalse(UiArtifactsSidecar.applyOriginalHeatmapSettings(new LinkedHashMap<>(), Map.of("settings", original)));
    }

    @Test
    void immutableCaptureMovesToUiWithoutCopyingOnDecisionThread() throws Exception {
        String pin = "iml_line_pin_cam3_f1042";
        UiArtifactsSidecar.FrozenFrame frame = UiArtifactsSidecar.freezeInspectionFrame(
                3, 1042L, "/" + pin, 1224, 1024, 1224 * 3,
                Map.of("shm_offset", 0L, "line_pinned", true)
        );
        // No source file is needed to hand off ownership; the UI worker does all frame I/O.
        assertEquals(FrameJpegWriter.imlShmFilePath(pin), frame.path());
        assertEquals("/" + pin, frame.shmName());
        assertTrue(frame.deleteWhenDone());
        assertTrue(new UiArtifactsSidecar(LogManager.getLogger(getClass())).retainsCaptureAfterScheduling());
    }

    @Test
    void acceptsSynchronizedCameraWavesWhilePublishersAreBusy() throws Exception {
        var sidecar = new UiArtifactsSidecar(LogManager.getLogger(getClass()));
        var executor = sidecar.startUiPublishExecutorIfEnabled(
                Map.of("enabled", true, "visuals_parallelism", 4, "visuals_queue_size", 8), 10);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(4);
        var completed = new java.util.concurrent.CountDownLatch(20);
        try {
            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            // Two ten-camera waves must fit even when no worker can drain the queue.
            for (int i = 0; i < 20; i++) {
                executor.execute(completed::countDown);
            }
            release.countDown();
            assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

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
    void publishesRawDiagnosticFrameAfterPositioningReject() {
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("positioning_ms", 40);
        cap.put("positioning_status", "FAIL");
        cap.put(InspectPositioningExecutor.HEADER_ALIGNED, false);
        assertTrue(UiArtifactsSidecar.shouldPublishUiFrameJpeg(REFERENCE, cap));
    }

    @Test
    void blocksWhilePositioningHasNoTerminalStatus() {
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("positioning_ms", 40);
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
