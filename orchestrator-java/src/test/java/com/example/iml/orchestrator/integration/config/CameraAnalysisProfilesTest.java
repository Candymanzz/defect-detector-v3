package com.example.iml.orchestrator.integration.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CameraAnalysisProfilesTest {

    @AfterEach
    void clear() {
        CameraAnalysisProfiles.setByCamera(Map.of());
    }

    @Test
    void resolvePrefersCameraMapOverFallbackProductType() {
        CameraAnalysisProfiles.setByCamera(Map.of(2, "bench-lan3"));
        assertEquals("bench-lan3", CameraAnalysisProfiles.resolve(2, "reference-product"));
    }

    @Test
    void resolveFallsBackWhenCameraMissing() {
        CameraAnalysisProfiles.setByCamera(Map.of(1, "bench-lan1"));
        assertEquals("reference-product", CameraAnalysisProfiles.resolve(9, "reference-product"));
        assertNull(CameraAnalysisProfiles.resolve(9, null));
    }
}
