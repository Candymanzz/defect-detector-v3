package com.example.iml.orchestrator.integration.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HeatmapArtifactRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void registerAndResolveHeatmapByToken() throws Exception {
        HeatmapArtifactRegistry registry = new HeatmapArtifactRegistry();
        Path heatmap = Files.write(tempDir.resolve("heatmap.u8"), new byte[]{1, 2, 3});

        String token = registry.register(0, heatmap);

        assertNotNull(token);
        assertEquals(32, token.length());
        assertEquals(heatmap.toAbsolutePath().normalize(), registry.resolve(token));
    }

    @Test
    void invalidatesPreviousTokenForSameCamera() throws Exception {
        HeatmapArtifactRegistry registry = new HeatmapArtifactRegistry();
        Path first = Files.write(tempDir.resolve("first.u8"), new byte[]{1});
        Path second = Files.write(tempDir.resolve("second.u8"), new byte[]{2});

        String firstToken = registry.register(1, first);
        String secondToken = registry.register(1, second);

        assertNull(registry.resolve(firstToken));
        assertEquals(second.toAbsolutePath().normalize(), registry.resolve(secondToken));
    }

    @Test
    void resolveRejectsMalformedToken() throws Exception {
        HeatmapArtifactRegistry registry = new HeatmapArtifactRegistry();
        Path heatmap = Files.write(tempDir.resolve("heatmap.u8"), new byte[]{1});

        String token = registry.register(2, heatmap);

        assertNull(registry.resolve(null));
        assertNull(registry.resolve(""));
        assertNull(registry.resolve("not-a-token"));
        assertNotNull(registry.resolve(token));
    }

    @Test
    void registerReturnsNullForMissingFile() {
        HeatmapArtifactRegistry registry = new HeatmapArtifactRegistry();

        assertNull(registry.register(3, tempDir.resolve("missing.u8")));
    }
}
