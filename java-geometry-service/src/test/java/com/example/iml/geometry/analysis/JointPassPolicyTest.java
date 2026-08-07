package com.example.iml.geometry.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JointPassPolicyTest {

    @Test
    void missingSeamFailsWhenJointRoiSet() {
        // Empty bucket / no label: joint=9999, seam_w=0, vis≈0 → FAIL
        assertFalse(OpenCvGeometryAnalysisService.evaluateJointPassForTest(
                true,
                false,
                false,
                9999.0,
                180.0,
                0.0,
                0.0,
                0.0,
                0.8,
                0.05,
                4.0,
                5.0,
                0.6
        ));
    }

    @Test
    void missingSeamWithHighVisibilityStillFails() {
        assertFalse(OpenCvGeometryAnalysisService.evaluateJointPassForTest(
                true,
                false,
                false,
                9999.0,
                180.0,
                0.0,
                0.0,
                0.45,
                0.8,
                0.05,
                4.0,
                5.0,
                0.6
        ));
    }

    @Test
    void narrowWidthBelowMinFails() {
        // width 0.15 with min 0.25 — real narrow defect (above noise floor)
        assertFalse(OpenCvGeometryAnalysisService.evaluateJointPassForTest(
                true,
                false,
                true,
                0.1,
                1.0,
                0.15,
                0.0,
                0.5,
                0.5,
                0.25,
                3.0,
                3.0,
                0.6
        ));
    }

    @Test
    void workingWidthAroundPointFourPassesWithSoftMin() {
        // Typical bench seam ~0.35–0.42 mm should PASS with min=0.25
        assertTrue(OpenCvGeometryAnalysisService.evaluateJointPassForTest(
                true,
                false,
                true,
                0.0,
                1.0,
                0.36,
                0.1,
                0.5,
                0.5,
                0.25,
                3.0,
                3.0,
                0.6
        ));
    }

    @Test
    void microWidthNoiseIsInconclusivePassEvenWithHighVisibility() {
        // Production FP: seam_w≈0.04–0.07, seam_vis=1.0 (Canny double-edge)
        assertTrue(OpenCvGeometryAnalysisService.evaluateJointPassForTest(
                true,
                false,
                true,
                0.20,
                0.12,
                0.046,
                0.0,
                1.0,
                0.5,
                0.25,
                3.0,
                3.0,
                0.6
        ));
    }

    @Test
    void validSeamWithinBandPasses() {
        assertTrue(OpenCvGeometryAnalysisService.evaluateJointPassForTest(
                true,
                false,
                true,
                0.0,
                1.0,
                1.2,
                0.2,
                0.4,
                0.8,
                0.5,
                3.0,
                3.0,
                0.6
        ));
    }

    @Test
    void excessiveTaperFails() {
        assertFalse(OpenCvGeometryAnalysisService.evaluateJointPassForTest(
                true,
                false,
                true,
                0.0,
                1.0,
                1.2,
                0.9,
                0.4,
                0.8,
                0.5,
                3.0,
                8.0,
                0.6
        ));
    }

    @Test
    void visibilityOnlyModeNeverFails() {
        assertTrue(OpenCvGeometryAnalysisService.evaluateJointPassForTest(
                true,
                true,
                false,
                9999.0,
                180.0,
                0.0,
                0.0,
                0.9,
                0.8,
                0.5,
                3.0,
                3.0,
                0.6
        ));
    }
}
