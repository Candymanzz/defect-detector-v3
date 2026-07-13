package com.example.iml.orchestrator.integration.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredCamerasTest {

    @Test
    void enabledIdsSkipsDisabledAndSorts() {
        Map<String, Object> root = Map.of(
                "cameras",
                List.of(
                        Map.of("id", 3, "enabled", true),
                        Map.of("id", 1, "enabled", false),
                        Map.of("id", 0, "enabled", true)
                )
        );

        assertEquals(List.of(0, 3), ConfiguredCameras.enabledIds(root));
    }

    @Test
    void analysisProfilePrefersExplicitField() {
        Map<String, Object> camera = Map.of(
                "analysis_profile", "profile-a",
                "product_type", "legacy"
        );

        assertEquals("profile-a", ConfiguredCameras.analysisProfileForCamera(camera, 5));
    }

    @Test
    void analysisProfileFallsBackToLegacyProductType() {
        Map<String, Object> camera = Map.of("product_type", "bench");

        assertEquals("bench", ConfiguredCameras.analysisProfileForCamera(camera, 2));
    }

    @Test
    void analysisProfileDefaultsToCameraId() {
        assertEquals("camera-7", ConfiguredCameras.analysisProfileForCamera(Map.of(), 7));
    }

    @Test
    void analysisProfileByCameraIdBuildsMap() {
        Map<String, Object> root = Map.of(
                "cameras",
                List.of(
                        Map.of("id", 1, "enabled", true, "analysis_profile", "p1"),
                        Map.of("id", 2, "enabled", false, "analysis_profile", "ignored")
                )
        );

        Map<Integer, String> profiles = ConfiguredCameras.analysisProfileByCameraId(root);

        assertEquals(1, profiles.size());
        assertEquals("p1", profiles.get(1));
        assertTrue(ConfiguredCameras.productTypeByCameraId(root).containsKey(1));
    }
}
