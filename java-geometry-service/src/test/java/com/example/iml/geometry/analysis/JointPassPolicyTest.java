package com.example.iml.geometry.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JointPassPolicyTest {

    @Test
    void missingSeamWithLowVisibilityIsInconclusivePass() {
        assertTrue(OpenCvGeometryAnalysisService.evaluateJointPassForTest(
                true,
                false,
                false,
                9999.0,
                180.0,
                0.0,
                0.0,
                0.8,
                0.05,
                4.0,
                5.0
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
                0.45,
                0.8,
                0.05,
                4.0,
                5.0
        ));
    }

    @Test
    void microWidthWithLowVisibilityIsInconclusivePass() {
        assertTrue(OpenCvGeometryAnalysisService.evaluateJointPassForTest(
                true,
                false,
                true,
                0.45,
                1.0,
                0.08,
                0.1,
                0.8,
                0.5,
                3.0,
                3.0
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
                0.4,
                0.8,
                0.5,
                3.0,
                3.0
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
                0.9,
                0.8,
                0.5,
                3.0,
                3.0
        ));
    }
}
