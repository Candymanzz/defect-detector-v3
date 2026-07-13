package com.example.iml.orchestrator.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlFileConfigLoaderTest {

    @TempDir
    Path tempDir;

  private final YamlFileConfigLoader loader = new YamlFileConfigLoader();

    @Test
    void loadsSimpleYaml() throws Exception {
        Path file = tempDir.resolve("app.yaml");
        Files.writeString(file, """
                cameras:
                  - id: 1
                    enabled: true
                integration:
                  save_captures:
                    enabled: false
                """);

        Map<String, Object> root = loader.load(file);

        assertInstanceOf(List.class, root.get("cameras"));
        @SuppressWarnings("unchecked")
        Map<String, Object> integration = (Map<String, Object>) root.get("integration");
        @SuppressWarnings("unchecked")
        Map<String, Object> saveCaptures = (Map<String, Object>) integration.get("save_captures");
        assertEquals(false, saveCaptures.get("enabled"));
    }

    @Test
    void mergesImportsDeeply() throws Exception {
        Path base = tempDir.resolve("base.yaml");
        Path child = tempDir.resolve("child.yaml");
        Files.writeString(base, """
                cameras:
                  - id: 0
                    enabled: true
                integration:
                  timing_stages_log:
                    enabled: true
                """);
        Files.writeString(child, """
                imports:
                  - base.yaml
                integration:
                  timing_stages_log:
                    file: logs/custom.jsonl
                  save_captures:
                    enabled: true
                """);

        Map<String, Object> root = loader.load(child);

        @SuppressWarnings("unchecked")
        Map<String, Object> integration = (Map<String, Object>) root.get("integration");
        @SuppressWarnings("unchecked")
        Map<String, Object> timing = (Map<String, Object>) integration.get("timing_stages_log");
        assertEquals(true, timing.get("enabled"));
        assertEquals("logs/custom.jsonl", timing.get("file"));
        @SuppressWarnings("unchecked")
        Map<String, Object> saveCaptures = (Map<String, Object>) integration.get("save_captures");
        assertEquals(true, saveCaptures.get("enabled"));
        assertInstanceOf(List.class, root.get("cameras"));
    }

    @Test
    void detectsCyclicImports() throws Exception {
        Path a = tempDir.resolve("a.yaml");
        Path b = tempDir.resolve("b.yaml");
        Files.writeString(a, "imports:\n  - b.yaml\nkey: a\n");
        Files.writeString(b, "imports:\n  - a.yaml\nkey: b\n");

        IOException ex = assertThrows(IOException.class, () -> loader.load(a));
        assertTrue(ex.getMessage().contains("циклический imports"));
    }
}
