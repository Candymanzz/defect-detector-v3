package com.example.iml.geometry.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RimSkewPolicyTest {

    @Test
    void siblingVisibilityNeverRejectsEvenWithHugeSkew() {
        // Line-2 cam6/9: angled buckets see optical yellow wedge — must not FAIL.
        assertTrue(OpenCvGeometryAnalysisService.evaluateRimSkewPassForTest(
                true,
                true,
                true,
                12.0,
                3.0,
                2.5,
                0.8
        ));
    }

    @Test
    void noJointRoiNeverRejects() {
        assertTrue(OpenCvGeometryAnalysisService.evaluateRimSkewPassForTest(
                false,
                false,
                true,
                12.0,
                3.0,
                2.5,
                0.8
        ));
    }

    @Test
    void jointCameraRejectsWhenSkewExceedsLimit() {
        assertFalse(OpenCvGeometryAnalysisService.evaluateRimSkewPassForTest(
                true,
                false,
                true,
                5.0,
                0.1,
                2.5,
                0.8
        ));
    }

    @Test
    void jointCameraPassesWhenWithinLimits() {
        assertTrue(OpenCvGeometryAnalysisService.evaluateRimSkewPassForTest(
                true,
                false,
                true,
                1.0,
                0.2,
                2.5,
                0.8
        ));
    }

    @Test
    void inactiveRimAlwaysPassesOnJointCamera() {
        assertTrue(OpenCvGeometryAnalysisService.evaluateRimSkewPassForTest(
                true,
                false,
                false,
                99.0,
                99.0,
                2.5,
                0.8
        ));
    }
}
