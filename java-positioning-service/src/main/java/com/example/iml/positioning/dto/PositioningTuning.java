package com.example.iml.positioning.dto;

/**
 * Runtime knobs for alignment quality gates (defaults match {@code BucketPositioningService}).
 */
public record PositioningTuning(
        double alignFailAbsdiff,
        double alignFailAbsdiffHard,
        double alignFailResidualPx,
        double eccSkipNcc,
        double eccSkipAbsdiff,
        double eccSkipResidualPx
) {
    public static PositioningTuning defaults() {
        return new PositioningTuning(10.0, 16.0, 10.0, 0.88, 5.0, 3.0);
    }
}
