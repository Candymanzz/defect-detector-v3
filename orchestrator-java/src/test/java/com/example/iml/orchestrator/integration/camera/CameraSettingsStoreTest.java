package com.example.iml.orchestrator.integration.camera;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraSettingsStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void mergeAndSavePersistsBetweenOpens() throws Exception {
        Path storagePath = tempDir.resolve("camera_runtime_settings.json");
        CameraSettingsStore store = CameraSettingsStore.open(storagePath);
        store.mergeAndSave(0, Map.of("exposure_us", 4500, "gain_db", 2.5));
        store.mergeAndSave(1, Map.of("gamma", 1.2));

        CameraSettingsStore reloaded = CameraSettingsStore.open(storagePath);
        assertEquals(4500, reloaded.settingsForCamera(0).get("exposure_us"));
        assertEquals(2.5, reloaded.settingsForCamera(0).get("gain_db"));
        assertEquals(1.2, reloaded.settingsForCamera(1).get("gamma"));
    }

    @Test
    void mergeAndSaveUpdatesExistingCamera() throws Exception {
        Path storagePath = tempDir.resolve("camera_runtime_settings.json");
        CameraSettingsStore store = CameraSettingsStore.open(storagePath);
        store.mergeAndSave(2, Map.of("exposure_us", 3000));
        store.mergeAndSave(2, Map.of("gain_db", 4.0));

        CameraSettingsStore reloaded = CameraSettingsStore.open(storagePath);
        Map<String, Object> settings = reloaded.settingsForCamera(2);
        assertEquals(3000, settings.get("exposure_us"));
        assertEquals(4.0, settings.get("gain_db"));
    }

    @Test
    void openMissingFileStartsEmpty() throws Exception {
        Path storagePath = tempDir.resolve("missing.json");
        CameraSettingsStore store = CameraSettingsStore.open(storagePath);
        assertTrue(store.allSettings().isEmpty());
        assertTrue(store.settingsForCamera(0).isEmpty());
        assertTrue(Files.notExists(storagePath));
    }
}
