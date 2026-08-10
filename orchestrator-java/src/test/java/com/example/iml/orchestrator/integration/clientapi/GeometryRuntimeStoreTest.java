package com.example.iml.orchestrator.integration.clientapi;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryRuntimeStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void replaceProfilePersistsBetweenOpens() throws Exception {
        Path storagePath = tempDir.resolve("geometry_runtime_settings.json");
        GeometryRuntimeStore store = GeometryRuntimeStore.open(storagePath);
        store.replaceProfileAndSave("__default__", Map.of("maxShiftMm", 0.75, "jointSeamSegmentationEnabled", true));
        store.replaceProfileAndSave("product-a", Map.of("maxShiftMm", 1.25));

        GeometryRuntimeStore reloaded = GeometryRuntimeStore.open(storagePath);
        assertEquals(0.75, reloaded.allProfiles().get("__default__").get("maxShiftMm"));
        assertEquals(true, reloaded.allProfiles().get("__default__").get("jointSeamSegmentationEnabled"));
        assertEquals(1.25, reloaded.allProfiles().get("product-a").get("maxShiftMm"));
    }

    @Test
    void emptyReplaceRemovesProfile() throws Exception {
        Path storagePath = tempDir.resolve("geometry_runtime_settings.json");
        GeometryRuntimeStore store = GeometryRuntimeStore.open(storagePath);
        store.replaceProfileAndSave("__default__", Map.of("maxShiftMm", 0.4));
        store.replaceProfileAndSave("__default__", Map.of());

        GeometryRuntimeStore reloaded = GeometryRuntimeStore.open(storagePath);
        assertTrue(reloaded.allProfiles().isEmpty());
    }

    @Test
    void geometryRuntimeConfigHydratesAndPersists() throws Exception {
        Path storagePath = tempDir.resolve("geometry_runtime_settings.json");
        GeometryRuntimeStore store = GeometryRuntimeStore.open(storagePath);
        GeometryRuntimeConfig config = new GeometryRuntimeConfig(store);
        config.mergeFromClient(null, Map.of("max_shift_mm", 0.9, "joint_seam_segmentation_sensitivity", 0.3));

        GeometryRuntimeConfig reloaded = new GeometryRuntimeConfig(GeometryRuntimeStore.open(storagePath));
        Map<String, Object> overrides = reloaded.overridesCopy(null);
        assertEquals(0.9, overrides.get("maxShiftMm"));
        assertEquals(0.3, overrides.get("jointSeamSegmentationSensitivity"));
        assertTrue(Files.isRegularFile(storagePath));
    }

    @Test
    void openMissingFileStartsEmpty() throws Exception {
        Path storagePath = tempDir.resolve("missing.json");
        GeometryRuntimeStore store = GeometryRuntimeStore.open(storagePath);
        assertTrue(store.allProfiles().isEmpty());
        assertTrue(Files.notExists(storagePath));
    }
}
