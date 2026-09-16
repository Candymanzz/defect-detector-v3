package com.example.iml.orchestrator.integration.trigger;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InspectionTriggerBusPhaseTest {

    @Test
    void broadcastsPhaseIdentityWithoutChangingCameraSetOrLegacySequence() throws Exception {
        List<Integer> cameraIds = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        Instant receivedAt = Instant.parse("2026-08-19T07:00:00.700Z");
        try (InspectionTriggerBus bus = new InspectionTriggerBus(cameraIds)) {
            long rawSequence = bus.reserveLineBroadcastSequence("io_input");
            TwoPhaseTriggerCorrelator.PhaseAssignment phase =
                    new TwoPhaseTriggerCorrelator.PhaseAssignment(1, 77, rawSequence);

            assertEquals(
                    10,
                    bus.dispatchLineBroadcast("io_input", rawSequence, receivedAt, null, phase)
            );
            for (int cameraId : cameraIds) {
                InspectionTriggerEvent event = bus.take(cameraId);
                assertEquals(cameraId, event.cameraId());
                assertEquals(rawSequence, event.sequence());
                assertEquals(1, event.phaseId());
                assertEquals(77, event.parentCycleId());
                assertEquals(rawSequence, event.rawTriggerSequence());
                assertEquals(receivedAt, event.receivedAt());
            }
        }
    }

    @Test
    void oldEventConstructorRetainsLegacyIdentity() {
        Instant receivedAt = Instant.parse("2026-08-19T07:00:00Z");
        InspectionTriggerEvent event =
                new InspectionTriggerEvent(3, 19, receivedAt, "legacy", false);

        assertEquals(0, event.phaseId());
        assertEquals(19, event.parentCycleId());
        assertEquals(19, event.rawTriggerSequence());
    }
}
