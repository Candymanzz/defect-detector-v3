package com.example.iml.orchestrator.integration.lighting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightBrightnessStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void saveFromClientPersistsBetweenOpens() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> ls = new LinkedHashMap<>();
        ls.put("enabled", false);
        ls.put("base_url", "http://127.0.0.1:5080");
        ls.put("cameras", List.of(
                Map.of("camera_id", 0, "mode", "pair", "brightness_percent", 80),
                Map.of("camera_id", 3, "mode", "pair", "brightness_percent", 80)
        ));
        root.put("light_servers", ls);

        LightTriggerClient client = new LightTriggerClient(LightServersConfig.fromRootYaml(root));
        client.setBrightnessPercent(42);
        client.setBrightnessPercent("camera-3", 55);

        Path storagePath = tempDir.resolve("light_brightness_settings.json");
        LightBrightnessStore store = LightBrightnessStore.open(storagePath);
        store.saveFromClient(client);

        LightBrightnessStore reloaded = LightBrightnessStore.open(storagePath);
        LightBrightnessUpdate update = reloaded.toUpdate();

        assertEquals(42, update.globalPercent());
        assertEquals(42, update.perEndpoint().get("camera-0"));
        assertEquals(55, update.perEndpoint().get("camera-3"));
    }

    @Test
    void openMissingFileStartsEmpty() throws Exception {
        Path storagePath = tempDir.resolve("missing.json");
        LightBrightnessStore store = LightBrightnessStore.open(storagePath);
        assertTrue(store.toUpdate().isEmpty());
    }

    @Test
    void openStripsUtf8Bom() throws Exception {
        Path storagePath = tempDir.resolve("bom.json");
        String json = "\uFEFF{\"version\":1,\"default_brightness_percent\":67,\"endpoints\":{\"camera-0\":67}}";
        java.nio.file.Files.writeString(storagePath, json);

        LightBrightnessStore store = LightBrightnessStore.open(storagePath);
        LightBrightnessUpdate update = store.toUpdate();

        assertEquals(67, update.globalPercent());
        assertEquals(67, update.perEndpoint().get("camera-0"));
    }
}
