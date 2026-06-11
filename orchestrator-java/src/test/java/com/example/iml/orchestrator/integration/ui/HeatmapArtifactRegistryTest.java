package com.example.iml.orchestrator.integration.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class HeatmapArtifactRegistryTest {

    @Test
    void registeredArtifactIsIndependentFromMutableSource() throws Exception {
        Path source = Files.createTempFile("artifact-source-", ".u8");
        Files.write(source, new byte[]{1, 2, 3});
        HeatmapArtifactRegistry registry = new HeatmapArtifactRegistry();

        String token = registry.register(0, source);
        Files.write(source, new byte[]{9, 9, 9});

        Path artifact = registry.resolve(token);
        assertNotNull(artifact);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(artifact));
    }
}
