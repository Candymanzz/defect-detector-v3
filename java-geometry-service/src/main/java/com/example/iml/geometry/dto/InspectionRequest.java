package com.example.iml.geometry.dto;

import java.util.List;

public record InspectionRequest(
        String referenceImageBase64,
        String currentImageBase64,
        RoiRect mainRoi,
        List<NormPoint> mainRoiPolygonNorm,
        RoiRect jointRoi,
        List<NormPoint> jointRoiPolygonNorm,
        RoiRect wrinklesRoi,
        double pixelsToMm,
        double maxShiftMm,
        double maxRotationDeg,
        double maxConcentricityMm,
        double maxJointDefectMm,
        double maxWrinklesScore,
        String jointMode,
        double jointMinWidthMm,
        double jointMaxWidthMm,
        double maxJointParallelismDeg,
        double maxJointTaperMm,
        boolean jointSeamSegmentationEnabled
) {
    public boolean jointVisibilityOnly() {
        return jointMode != null && "visibility".equalsIgnoreCase(jointMode.trim());
    }
}
