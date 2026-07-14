package com.example.iml.positioning.dto;

public record PositioningResponse(
        double shiftXmm,
        double shiftYmm,
        double rotationDeg,
        double[] homographyRefToCurrent,
        boolean alignmentPass,
        boolean overallPass,
        boolean alignedWritten,
        String outputShmName,
        int width,
        int height,
        int stride,
        String status,
        double stageMsOrb,
        double stageMsWarp,
        double stageMsEcc,
        double stageMsWrite,
        double stageMsTotal
) {
}
