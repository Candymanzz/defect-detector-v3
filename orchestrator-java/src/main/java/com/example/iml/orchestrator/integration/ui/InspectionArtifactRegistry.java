package com.example.iml.orchestrator.integration.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InspectionArtifactRegistry {

    public record Bundle(
            String id,
            int cameraId,
            long frameId,
            Path frameJpeg,
            Path cardJpeg,
            Path heatmapU8,
            long createdAtEpochMs
    ) {
    }

    private final Path root;
    private final ConcurrentHashMap<String, Bundle> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> latestIdByCamera = new ConcurrentHashMap<>();

    public InspectionArtifactRegistry(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        InspectionArtifactStoreSupport.cleanupOrphanedDirectories(this.root);
    }

    public synchronized Bundle register(int cameraId, long frameId, Path frameJpeg, Path heatmapU8) throws IOException {
        return register(cameraId, frameId, frameJpeg, null, heatmapU8);
    }

    public synchronized Bundle register(
            int cameraId,
            long frameId,
            Path frameJpeg,
            Path cardJpeg,
            Path heatmapU8
    ) throws IOException {
        if (frameJpeg == null || !Files.isRegularFile(frameJpeg)) {
            throw new IOException("inspection frame source is missing");
        }
        if (cardJpeg != null && !Files.isRegularFile(cardJpeg)) {
            throw new IOException("inspection card source is missing");
        }
        if (heatmapU8 != null && !Files.isRegularFile(heatmapU8)) {
            throw new IOException("inspection heatmap source is missing");
        }

        InspectionArtifactStoreSupport.cleanup(byId, latestIdByCamera, this::remove);
        Files.createDirectories(root);
        String id = InspectionArtifactStoreSupport.newToken();
        Path bundleDir = root.resolve(id);
        Files.createDirectories(bundleDir);
        Path storedFrame = bundleDir.resolve("frame.jpg");
        Path storedCard = bundleDir.resolve("card.jpg");
        Path storedHeatmap = heatmapU8 == null ? null : bundleDir.resolve("heatmap.u8");
        try {
            Files.copy(frameJpeg, storedFrame, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(cardJpeg == null ? frameJpeg : cardJpeg, storedCard, StandardCopyOption.REPLACE_EXISTING);
            if (heatmapU8 != null) {
                Files.copy(heatmapU8, storedHeatmap, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            InspectionArtifactStoreSupport.deleteBundleDirectory(bundleDir);
            throw e;
        }

        Bundle bundle = new Bundle(
                id,
                cameraId,
                frameId,
                storedFrame,
                storedCard,
                storedHeatmap,
                System.currentTimeMillis()
        );
        byId.put(id, bundle);
        String previousLatest = latestIdByCamera.put(cameraId, id);
        if (previousLatest != null && !previousLatest.equals(id)) {
            Bundle previous = byId.get(previousLatest);
            if (previous != null) {
                remove(previous);
            }
        }
        InspectionArtifactStoreSupport.cleanup(byId, latestIdByCamera, this::remove);
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
                bundle.cardJpeg(),
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
        if (!InspectionArtifactStoreSupport.TOKEN.matcher(id).matches()) {
            return Optional.empty();
        }
        Bundle bundle = byId.get(id);
        if (bundle == null
                || !Files.isRegularFile(bundle.frameJpeg())
                || !Files.isRegularFile(bundle.cardJpeg())
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
            case "card.jpg" -> bundle.cardJpeg();
            case "heatmap.u8" -> bundle.heatmapU8();
            default -> null;
        };
        return artifact == null ? null : Files.readAllBytes(artifact);
    }

    private void remove(Bundle bundle) {
        if (byId.remove(bundle.id(), bundle)) {
            InspectionArtifactStoreSupport.deleteBundleDirectory(bundle.frameJpeg().getParent());
        }
    }
}
