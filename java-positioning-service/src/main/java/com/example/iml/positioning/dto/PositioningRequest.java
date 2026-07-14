package com.example.iml.positioning.dto;

import java.util.List;

public record PositioningRequest(
        RoiRect mainRoi,
        List<NormPoint> mainRoiPolygonNorm,
        double pixelsToMm,
        double maxShiftMm,
        double maxRotationDeg,
        String outputShmName,
        boolean writeAligned
) {
}
