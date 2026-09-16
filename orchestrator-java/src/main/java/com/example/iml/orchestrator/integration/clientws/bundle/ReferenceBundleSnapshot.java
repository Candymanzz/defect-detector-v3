package com.example.iml.orchestrator.integration.clientws.bundle;

import java.util.List;

/**
 * Неизменяемый снимок принятого пакета эталонов (только RAM).
 */
public record ReferenceBundleSnapshot(
        String productType,
        int phaseId,
        int groupId,
        List<ReferenceViewSlot> views,
        int jointViewIndex,
        int heatmapWidth,
        int heatmapHeight,
        List<FpZoneNorm> fpZones,
        long acceptedAtEpochMs
) {
    public ReferenceBundleSnapshot(
            String productType,
            List<ReferenceViewSlot> views,
            int jointViewIndex,
            int heatmapWidth,
            int heatmapHeight,
            List<FpZoneNorm> fpZones,
            long acceptedAtEpochMs
    ) {
        this(productType, 0, inferLegacyGroup(views), views, jointViewIndex, heatmapWidth, heatmapHeight, fpZones, acceptedAtEpochMs);
    }

    private static int inferLegacyGroup(List<ReferenceViewSlot> views) {
        if (views == null) {
            return 0;
        }
        return views.stream().mapToInt(view -> view.frame().cameraId()).min().orElse(0) / 5;
    }
}
