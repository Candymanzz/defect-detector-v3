package com.example.iml.geometry.dto;

public record InspectionResponse(
        double shiftXmm,
        double shiftYmm,
        double rotationDeg,
        double[] homographyRefToCurrent,
        double concentricityMm,
        /** Radial deviation from etalon pose: hypot(shiftXmm, shiftYmm). */
        double deviationRadiusMm,
        double jointDefectMm,
        double jointParallelismDeg,
        double jointWidthMm,
        double jointWidthTopMm,
        double jointWidthBottomMm,
        double jointTaperMm,
        double jointVisibility,
        double wrinklesScore,
        boolean alignmentPass,
        boolean concentricityPass,
        boolean jointPass,
        boolean wrinklesPass,
        boolean overallPass,
        String debugImageBase64,
        String status
) {
}
