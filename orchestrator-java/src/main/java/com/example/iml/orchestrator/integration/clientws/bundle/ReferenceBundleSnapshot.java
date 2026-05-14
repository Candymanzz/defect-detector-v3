package com.example.iml.orchestrator.integration.clientws.bundle;

import java.util.List;

/**
 * Неизменяемый снимок принятого пакета эталонов (только RAM).
 */
public record ReferenceBundleSnapshot(
        String productType,
        List<ReferenceViewSlot> views,
        int jointViewIndex,
        int heatmapWidth,
        int heatmapHeight,
        List<FpZoneNorm> fpZones,
        long acceptedAtEpochMs
) {
}
