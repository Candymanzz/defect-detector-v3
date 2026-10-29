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
