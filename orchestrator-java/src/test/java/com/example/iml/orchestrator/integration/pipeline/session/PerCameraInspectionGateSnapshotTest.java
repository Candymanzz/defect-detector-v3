package com.example.iml.orchestrator.integration.pipeline.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerCameraInspectionGateSnapshotTest {

    @Test
    void snapshotAndRestoreInspectionEnabled() {
        PerCameraInspectionGate gate = PerCameraInspectionGate.fromCameras(List.of(
                Map.of("id", 0, "inspection_enabled", true),
                Map.of("id", 1, "inspection_enabled", false),
                Map.of("id", 2, "inspection_enabled", true)
        ));
        Map<Integer, Boolean> snapshot = gate.snapshotInspectionEnabled();
        gate.setInspectionEnabled(0, false);
        gate.setInspectionEnabled(1, true);
        gate.setInspectionEnabled(2, false);

        gate.restoreInspectionEnabled(snapshot);

        assertTrue(gate.isInspectionEnabled(0));
        assertFalse(gate.isInspectionEnabled(1));
        assertTrue(gate.isInspectionEnabled(2));
    }
}
