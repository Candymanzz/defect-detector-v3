package com.example.iml.orchestrator.integration.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectionArtifactRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void storesImmutableCopiesAndReadsThemByBundleId() throws Exception {
        Path root = tempDir.resolve("registry");
        Path frame = Files.write(tempDir.resolve("source.jpg"), new byte[]{1, 2, 3});
        Path heatmap = Files.write(tempDir.resolve("source.u8"), new byte[]{4, 5, 6});
        InspectionArtifactRegistry registry = new InspectionArtifactRegistry(root);

        InspectionArtifactRegistry.Bundle bundle = registry.register(2, 42, frame, heatmap);
        Files.write(frame, new byte[]{9});
        Files.delete(heatmap);

        assertArrayEquals(new byte[]{1, 2, 3}, registry.read(bundle.id(), "frame.jpg"));
        assertArrayEquals(new byte[]{4, 5, 6}, registry.read(bundle.id(), "heatmap.u8"));
        assertNull(registry.read(bundle.id(), "unknown"));
    }

    @Test
    void removesOrphanedBundlesFromPreviousProcess() throws Exception {
        Path root = tempDir.resolve("registry");
        Path orphan = root.resolve("0123456789abcdef0123456789abcdef");
        Files.createDirectories(orphan);
        Files.write(orphan.resolve("frame.jpg"), new byte[]{1});
        Files.write(orphan.resolve("heatmap.u8"), new byte[]{2});

        new InspectionArtifactRegistry(root);

        assertFalse(Files.exists(orphan));
        assertTrue(Files.isDirectory(root));
    }
}
