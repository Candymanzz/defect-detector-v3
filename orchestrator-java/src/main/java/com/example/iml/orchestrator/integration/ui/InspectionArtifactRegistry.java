package com.example.iml.orchestrator.integration.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.security.SecureRandom;
import java.util.stream.Stream;

public final class InspectionArtifactRegistry {

    public record Bundle(
            String id,
            int cameraId,
            long frameId,
            Path frameJpeg,
            Path heatmapU8,
            long createdAtEpochMs
    ) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN = Pattern.compile("^[0-9a-f]{32}$");
    private static final long RETENTION_MS = Duration.ofMinutes(30).toMillis();
    private static final int MAX_BUNDLES = 200;

    private final Path root;
    private final ConcurrentHashMap<String, Bundle> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> latestIdByCamera = new ConcurrentHashMap<>();

    public InspectionArtifactRegistry(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        cleanupOrphanedDirectories();
    }

    public synchronized Bundle register(int cameraId, long frameId, Path frameJpeg, Path heatmapU8) throws IOException {
        if (frameJpeg == null || !Files.isRegularFile(frameJpeg)) {
            throw new IOException("inspection frame source is missing");
        }
        if (heatmapU8 != null && !Files.isRegularFile(heatmapU8)) {
            throw new IOException("inspection heatmap source is missing");
        }

        cleanup();
        Files.createDirectories(root);
        String id = newToken();
        Path bundleDir = root.resolve(id);
        Files.createDirectories(bundleDir);
        Path storedFrame = bundleDir.resolve("frame.jpg");
        Path storedHeatmap = heatmapU8 == null ? null : bundleDir.resolve("heatmap.u8");
        try {
            Files.copy(frameJpeg, storedFrame, StandardCopyOption.REPLACE_EXISTING);
            if (heatmapU8 != null) {
                Files.copy(heatmapU8, storedHeatmap, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            deleteBundleDirectory(bundleDir);
            throw e;
        }

        Bundle bundle = new Bundle(id, cameraId, frameId, storedFrame, storedHeatmap, System.currentTimeMillis());
        byId.put(id, bundle);
        latestIdByCamera.put(cameraId, id);
        cleanup();
        return bundle;
    }

    public synchronized Bundle attachHeatmap(String rawId, Path heatmapU8) throws IOException {
        if (heatmapU8 == null || !Files.isRegularFile(heatmapU8)) {
            throw new IOException("inspection heatmap source is missing");
        }

        Bundle bundle = resolve(rawId).orElseThrow(() -> new IOException("inspection artifact bundle is missing"));
        Path storedHeatmap = bundle.frameJpeg().getParent().resolve("heatmap.u8");
        Files.copy(heatmapU8, storedHeatmap, StandardCopyOption.REPLACE_EXISTING);
        Bundle updatedBundle = new Bundle(
                bundle.id(),
                bundle.cameraId(),
                bundle.frameId(),
                bundle.frameJpeg(),
                storedHeatmap,
                bundle.createdAtEpochMs()
        );
        byId.put(updatedBundle.id(), updatedBundle);
        return updatedBundle;
    }

    public synchronized Optional<Bundle> resolve(String rawId) {
        if (rawId == null) {
            return Optional.empty();
        }
        String id = rawId.trim();
        if (!TOKEN.matcher(id).matches()) {
            return Optional.empty();
        }
        Bundle bundle = byId.get(id);
        if (bundle == null
                || !Files.isRegularFile(bundle.frameJpeg())
                || (bundle.heatmapU8() != null && !Files.isRegularFile(bundle.heatmapU8()))) {
            return Optional.empty();
        }
        return Optional.of(bundle);
    }

    public synchronized byte[] read(String rawId, String artifactName) throws IOException {
        Bundle bundle = resolve(rawId).orElse(null);
        if (bundle == null) {
            return null;
        }
        Path artifact = switch (artifactName) {
            case "frame.jpg" -> bundle.frameJpeg();
            case "heatmap.u8" -> bundle.heatmapU8();
            default -> null;
        };
        return artifact == null ? null : Files.readAllBytes(artifact);
    }

    private void cleanup() {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        byId.values().stream()
                .filter(bundle -> !bundle.id().equals(latestIdByCamera.get(bundle.cameraId())))
                .filter(bundle -> bundle.createdAtEpochMs() < cutoff)
                .forEach(this::remove);

        int overflow = byId.size() - MAX_BUNDLES;
        if (overflow <= 0) {
            return;
        }
        byId.values().stream()
                .filter(bundle -> !bundle.id().equals(latestIdByCamera.get(bundle.cameraId())))
                .sorted(Comparator.comparingLong(Bundle::createdAtEpochMs))
                .limit(overflow)
                .forEach(this::remove);
    }

    private void remove(Bundle bundle) {
        if (byId.remove(bundle.id(), bundle)) {
            deleteBundleDirectory(bundle.frameJpeg().getParent());
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void deleteBundleDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            Files.deleteIfExists(directory.resolve("frame.jpg"));
            Files.deleteIfExists(directory.resolve("heatmap.u8"));
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
        }
    }

    private void cleanupOrphanedDirectories() {
        try {
            Files.createDirectories(root);
            try (Stream<Path> entries = Files.list(root)) {
                entries.filter(Files::isDirectory)
                        .filter(path -> TOKEN.matcher(path.getFileName().toString()).matches())
                        .forEach(InspectionArtifactRegistry::deleteBundleDirectory);
            }
        } catch (IOException ignored) {
            // Best effort: stale files must not prevent the UI server from starting.
        }
    }
}
