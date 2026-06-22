package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectionResumeBoundaryTest {

    @Test
    void resumeDropsQueuedTriggersAndAcceptsTheNextSequence() throws Exception {
        PerCameraInspectionGate gate = gateForCamera(0);
        try (InspectionTriggerBus bus = new InspectionTriggerBus(List.of(0))) {
            gate.setTriggerBacklogDiscarder(bus::discardPendingThroughCurrentSequence);

            for (int sequence = 1; sequence <= 50; sequence++) {
                bus.publishBroadcast(InspectionTriggerEvent.lineBroadcast("line"));
            }

            gate.disableInspectionAndRequestCancel(0);
            gate.setInspectionEnabled(0, true);
            bus.publishBroadcast(InspectionTriggerEvent.lineBroadcast("line"));

            InspectionTriggerEvent resumedEvent = bus.take(0);
            assertEquals(51L, resumedEvent.sequence());
            assertFalse(gate.shouldDiscardTriggerAtResumeBoundary(0, resumedEvent.sequence()));
            assertTrue(gate.isFirstTriggerAfterResume(0, resumedEvent.sequence()));
        }
    }

    @Test
    void firstNewTriggerWaitsForCancelledCycleInsteadOfBeingLost() throws Exception {
        PerCameraInspectionGate gate = gateForCamera(0);
        assertEquals(PerCameraInspectionGate.BeginResult.STARTED, gate.tryBeginInspection(0));

        gate.disableInspectionAndRequestCancel(0);
        gate.setTriggerBacklogDiscarder(cameraId -> 50L);
        gate.setInspectionEnabled(0, true);

        Thread cycleFinisher = new Thread(() -> {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.endInspection(0);
        });
        cycleFinisher.start();

        assertTrue(gate.isFirstTriggerAfterResume(0, 51L));
        assertEquals(PerCameraInspectionGate.BeginResult.IN_FLIGHT, gate.tryBeginInspection(0));
        assertTrue(gate.awaitInspectionIdle(0, 1_000L));
        assertEquals(PerCameraInspectionGate.BeginResult.STARTED, gate.tryBeginInspection(0));
        gate.markResumeTriggerAccepted(0);
        assertFalse(gate.isFirstTriggerAfterResume(0, 52L));

        gate.endInspection(0);
        cycleFinisher.join();
    }

    private static PerCameraInspectionGate gateForCamera(int cameraId) {
        return PerCameraInspectionGate.fromCameras(List.of(Map.of(
                "id", cameraId,
                "inspection_enabled", true
        )));
    }
}
