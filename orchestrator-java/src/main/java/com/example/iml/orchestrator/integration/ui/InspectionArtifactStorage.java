package com.example.iml.orchestrator.integration.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class InspectionArtifactStorage {
    private static final SecureRandom RANDOM = new SecureRandom();

    private InspectionArtifactStorage() {
    }

    static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static void deleteBundleDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            Files.deleteIfExists(directory.resolve("frame.jpg"));
            Files.deleteIfExists(directory.resolve("card.jpg"));
            Files.deleteIfExists(directory.resolve("heatmap.u8"));
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
        }
    }

    static void cleanupOrphanedDirectories(Path root, Pattern token) {
        try {
            Files.createDirectories(root);
            try (Stream<Path> entries = Files.list(root)) {
                entries.filter(Files::isDirectory)
                        .filter(path -> token.matcher(path.getFileName().toString()).matches())
                        .forEach(InspectionArtifactStorage::deleteBundleDirectory);
            }
        } catch (IOException ignored) {
            // Best effort: stale files must not prevent the UI server from starting.
        }
    }
}
