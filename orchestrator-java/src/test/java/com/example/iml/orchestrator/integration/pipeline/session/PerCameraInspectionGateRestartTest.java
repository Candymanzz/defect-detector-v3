package com.example.iml.orchestrator.integration.pipeline.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PerCameraInspectionGateRestartTest {

    @Test
    void startAfterSequenceIgnoresOldGroupAndStartsNextGroup() {
        PerCameraInspectionGate gate = gate(false);

        assertTrue(gate.armAllInspectionAfter(110L));

        assertEquals(PerCameraInspectionGate.BeginResult.DISABLED, gate.tryBeginInspection(0, 110L));
        assertFalse(gate.tryBeginPreviewCapture(0));
        assertEquals(PerCameraInspectionGate.BeginResult.STARTED, gate.tryBeginInspection(0, 111L));
        gate.endInspection(0);
    }

    @Test
    void stoppedPreviewIsSerializedWithInspectionCapture() {
        PerCameraInspectionGate gate = gate(false);

        assertTrue(gate.tryBeginPreviewCapture(0));
        assertFalse(gate.tryBeginPreviewCapture(0));
        gate.endPreviewCapture(0);
        assertTrue(gate.tryBeginPreviewCapture(0));
        gate.endPreviewCapture(0);
    }

    @Test
    void phaseOneBlocksUntilPhaseZeroCaptureEnds() throws Exception {
        PerCameraInspectionGate gate = gate(true);

        assertEquals(
                PerCameraInspectionGate.BeginResult.STARTED,
                gate.tryBeginInspection(0, 55L, 0, 100L)
        );

        CountDownLatch phaseOneStarted = new CountDownLatch(1);
        Thread phaseOne = new Thread(() -> {
            assertEquals(
                    PerCameraInspectionGate.BeginResult.STARTED,
                    gate.tryBeginInspection(0, 55L, 1, 101L)
            );
            phaseOneStarted.countDown();
        }, "phase-one-begin");
        phaseOne.start();

        assertFalse(phaseOneStarted.await(80, TimeUnit.MILLISECONDS));

        gate.endInspection(0, 55L, 0);
        assertTrue(phaseOneStarted.await(2, TimeUnit.SECONDS));
        phaseOne.join(TimeUnit.SECONDS.toMillis(2));

        assertEquals(
                PerCameraInspectionGate.BeginResult.IN_FLIGHT,
                gate.tryBeginInspection(0, 55L, 1, 101L)
        );

        gate.endInspection(0, 55L, 1);
        assertFalse(gate.isInspectionInFlight(0));
        assertTrue(gate.awaitAllIdle(10));
    }

    private static PerCameraInspectionGate gate(boolean enabled) {
        return PerCameraInspectionGate.fromCameras(List.of(Map.of(
                "id", 0,
                "inspection_enabled", enabled
        )));
    }
}
