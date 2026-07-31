package com.example.iml.orchestrator.integration.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Retention / token / directory helpers for {@link InspectionArtifactRegistry}. */
final class InspectionArtifactStoreSupport {

    static final SecureRandom RANDOM = new SecureRandom();
    static final Pattern TOKEN = Pattern.compile("^[0-9a-f]{32}$");
    static final long RETENTION_MS = Duration.ofMinutes(2).toMillis();
    static final int MAX_BUNDLES = 40;

    private InspectionArtifactStoreSupport() {
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

    static void cleanup(
            ConcurrentHashMap<String, InspectionArtifactRegistry.Bundle> byId,
            ConcurrentHashMap<Integer, String> latestIdByCamera,
            java.util.function.Consumer<InspectionArtifactRegistry.Bundle> remover
    ) {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        byId.values().stream()
                .filter(bundle -> !bundle.id().equals(latestIdByCamera.get(bundle.cameraId())))
                .filter(bundle -> bundle.createdAtEpochMs() < cutoff)
                .forEach(remover);

        int overflow = byId.size() - MAX_BUNDLES;
        if (overflow <= 0) {
            return;
        }
        byId.values().stream()
                .filter(bundle -> !bundle.id().equals(latestIdByCamera.get(bundle.cameraId())))
                .sorted(Comparator.comparingLong(InspectionArtifactRegistry.Bundle::createdAtEpochMs))
                .limit(overflow)
                .forEach(remover);
    }

    static void cleanupOrphanedDirectories(Path root) {
        try {
            Files.createDirectories(root);
            try (Stream<Path> entries = Files.list(root)) {
                entries.filter(Files::isDirectory)
                        .filter(path -> TOKEN.matcher(path.getFileName().toString()).matches())
                        .forEach(InspectionArtifactStoreSupport::deleteBundleDirectory);
            }
        } catch (IOException ignored) {
            // Best effort: stale files must not prevent the UI server from starting.
        }
    }
}
