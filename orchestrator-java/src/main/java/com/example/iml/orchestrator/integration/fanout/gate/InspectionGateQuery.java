package com.example.iml.orchestrator.integration.fanout.gate;

import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;

/**
 * Запросы к {@link PerCameraInspectionGate} для PLC/UI lock-правил.
 */
public final class InspectionGateQuery {
    private final PerCameraInspectionGate inspectionGate;

    public InspectionGateQuery(PerCameraInspectionGate inspectionGate) {
        this.inspectionGate = inspectionGate;
    }

    public boolean inspectionInFlight() {
        return inspectionGate != null && inspectionGate.hasAnyInspectionInFlight();
    }
}
