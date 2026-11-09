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
    @Test
    void resolvesIpReferencesAfterImportsIncludingUrlsAndCommandLists() throws Exception {
        Files.writeString(tempDir.resolve("ips.yaml"), "camera_0: '192.0.2.12'\nloopback: '127.0.0.1'\n");
        Files.writeString(tempDir.resolve("block.yaml"), "cameras: [{id: 0, ip: '${ip:camera_0}'}]\n");
        Files.writeString(tempDir.resolve("config.yaml"), """
                imports: [block.yaml]
                ip_config: ips.yaml
                url: http://${ip:loopback}:8000/health
                command: [server, '${ip:loopback}']
                """);
        var root = loader.load(tempDir.resolve("config.yaml"));
        assertEquals("http://127.0.0.1:8000/health", root.get("url"));
        assertEquals(List.of("server", "127.0.0.1"), root.get("command"));
        assertEquals("192.0.2.12", ((Map<?, ?>)((List<?>)root.get("cameras")).get(0)).get("ip"));
    }

    @Test
    void rejectsMissingFileMissingKeyAndInvalidAddress() throws Exception {
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config, "ip_config: ips.yaml\nip: '${ip:camera_0}'\n");
        assertTrue(assertThrows(IOException.class, () -> loader.load(config)).getMessage().contains("IP config"));
        Files.writeString(tempDir.resolve("ips.yaml"), "other: '127.0.0.1'\n");
        assertTrue(assertThrows(IOException.class, () -> loader.load(config)).getMessage().contains("camera_0"));
        Files.writeString(tempDir.resolve("ips.yaml"), "camera_0: '999.1.2.3'\n");
        assertTrue(assertThrows(IOException.class, () -> loader.load(config)).getMessage().contains("Invalid IPv4"));
    }

    @Test
    void shippedYamlExportsOneResolvedNativeCameraConfiguration() throws Exception {
        Path directory = ShippedConfigFixture.copyTo(tempDir.resolve("config"));
        var root = loader.load(directory.resolve("config.yaml"));
        Path output = com.example.iml.orchestrator.integration.config.CameraWorkerPaths.prepareWorkerConfig(tempDir, root);
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.toFile());
        assertEquals(10, json.get("cameras").size());
        assertEquals("127.0.0.1", json.get("cameras").get(0).get("ip").asText());
        assertEquals("Bayer RG 12 Packed", json.get("frame").get("format").asText());
        assertEquals("line0", json.get("capture_trigger_mode").asText());
        assertTrue(!Files.readString(output).contains("${ip:"));
        assertTrue(!json.has("integration"));
        root.put("frame_timeout_ms", 12345);
        com.example.iml.orchestrator.integration.config.CameraWorkerPaths.prepareWorkerConfig(tempDir, root);
        assertEquals(12345, new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.toFile()).get("frame_timeout_ms").asInt());
    }

}
