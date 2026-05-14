package com.example.iml.orchestrator.integration.clientws.bundle;

import java.util.List;

/**
 * FP-зона в нормализованных координатах heatmap [0,1].
 */
public record FpZoneNorm(String id, String note, List<PointNorm> pointsNormHeatmap) {
    public record PointNorm(double x, double y) {
    }
}
