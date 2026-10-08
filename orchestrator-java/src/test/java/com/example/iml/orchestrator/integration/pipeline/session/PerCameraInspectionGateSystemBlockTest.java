package com.example.iml.orchestrator.integration.pipeline.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerCameraInspectionGateSystemBlockTest {

    @Test
    void systemBlockedPreventsNewInspectionCycles() {
        PerCameraInspectionGate gate = PerCameraInspectionGate.fromCameras(List.of(
                Map.of("id", 0, "inspection_enabled", true)
        ));
        assertEquals(PerCameraInspectionGate.BeginResult.STARTED, gate.tryBeginInspection(0, 10L));
        gate.endInspection(0);

        gate.setSystemBlocked(true);
        assertEquals(PerCameraInspectionGate.BeginResult.DISABLED, gate.tryBeginInspection(0, 11L));

        gate.setSystemBlocked(false);
        assertEquals(PerCameraInspectionGate.BeginResult.STARTED, gate.tryBeginInspection(0, 12L));
        gate.endInspection(0);
    }
}
