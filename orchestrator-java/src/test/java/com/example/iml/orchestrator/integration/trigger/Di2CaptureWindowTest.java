package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Di2CaptureWindowTest {

    @Test
    void opensCountsToTargetThenCloses() {
        PerCameraInspectionGate gate = PerCameraInspectionGate.fromCameras(List.of(
                Map.of("id", 0, "inspection_enabled", true),
                Map.of("id", 1, "inspection_enabled", true)
        ));
        Di2CaptureWindow window = new Di2CaptureWindow(
                LogManager.getLogger(Di2CaptureWindowTest.class),
                gate,
                2,
                2,
                60_000L
        );
        assertEquals(4, window.targetFrames());
        assertTrue(window.tryOpen());
        assertFalse(window.tryOpen());
        assertTrue(window.isOpen());

        gate.setAwaitPriorPhase(false);
        assertEquals(PerCameraInspectionGate.BeginResult.STARTED, gate.tryBeginInspection(0, 1L, 0, 10L));
        assertEquals(PerCameraInspectionGate.BeginResult.STARTED, gate.tryBeginInspection(0, 1L, 1, 11L));

        window.onCaptureOk(0, 0, 1L);
        window.onCaptureOk(0, 1, 1L);
        window.onCaptureOk(1, 0, 1L);
        assertTrue(window.isOpen());
        window.onCaptureOk(1, 1, 1L);
        assertFalse(window.isOpen());
        assertFalse(gate.isCancelRequested(0));
        assertTrue(gate.isInspectionInFlight(0));
        gate.endInspection(0, 1L, 0);
        gate.endInspection(0, 1L, 1);
    }

    @Test
    void ignoresCaptureOkWhenClosed() {
        PerCameraInspectionGate gate = PerCameraInspectionGate.fromCameras(List.of(
                Map.of("id", 0, "inspection_enabled", true)
        ));
        Di2CaptureWindow window = new Di2CaptureWindow(
                LogManager.getLogger(Di2CaptureWindowTest.class),
                gate,
                1,
                2,
                60_000L
        );
        assertTrue(window.tryOpen());
        window.onCaptureOk(0, 0, 1L);
        window.onCaptureOk(0, 1, 1L);
        assertFalse(window.isOpen());
        window.onCaptureOk(0, 0, 2L);
        assertTrue(window.tryOpen());
        assertTrue(window.isOpen());
        window.close("test");
        assertFalse(window.isOpen());
    }

    @Test
    void duplicateCameraPhaseDoesNotCompleteBundle() {
        PerCameraInspectionGate gate = PerCameraInspectionGate.fromCameras(List.of(
                Map.of("id", 0, "inspection_enabled", true),
                Map.of("id", 1, "inspection_enabled", true)
        ));
        Di2CaptureWindow window = new Di2CaptureWindow(
                LogManager.getLogger(Di2CaptureWindowTest.class),
                gate,
                2,
                2,
                60_000L
        );
        assertTrue(window.tryOpen());
        window.onCaptureOk(0, 0, 1L);
        window.onCaptureOk(0, 0, 1L);
        window.onCaptureOk(0, 1, 1L);
        window.onCaptureOk(1, 0, 1L);
        assertTrue(window.isOpen());
        window.onCaptureOk(1, 1, 1L);
        assertFalse(window.isOpen());
    }

    @Test
    void di2LowGraceAcceptsThenCloses() throws Exception {
        PerCameraInspectionGate gate = PerCameraInspectionGate.fromCameras(List.of(
                Map.of("id", 0, "inspection_enabled", true)
        ));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            Di2CaptureWindow window = new Di2CaptureWindow(
                    LogManager.getLogger(Di2CaptureWindowTest.class),
                    gate,
                    1,
                    2,
                    60_000L,
                    scheduler,
                    false
            );
            assertTrue(window.tryOpen());
            window.scheduleCloseAfterDi2Low(80L);
            window.onCaptureOk(0, 0, 1L);
            assertTrue(window.isOpen());
            Thread.sleep(150L);
            assertFalse(window.isOpen());
        } finally {
            scheduler.shutdownNow();
        }
    }
}
