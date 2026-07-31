package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.ui.archive.FrameArchiveIndex;
import com.example.iml.orchestrator.integration.ui.archive.FrameArchivePaths;
import com.example.iml.orchestrator.integration.ui.archive.FrameArchiveRetention;
import com.example.iml.orchestrator.integration.ui.archive.FrameArchiveSaveSupport;
import com.example.iml.orchestrator.integration.ui.archive.FrameArchiveWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class FrameArchiveService implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(FrameArchiveService.class);

    public record SaveRequest(
            int cameraId,
            long frameId,
            long inspectionId,
            String productType,
            String detectorId,
            InspectionDecision decision,
            Path frameJpeg,
            Path heatmapU8,
            int heatmapWidth,
            int heatmapHeight
    ) {
    }

    public record ArchivedFrame(
            long frameId,
            long inspectionId,
            boolean overallPass,
            String action,
            double anomalyScore,
            String pythonStatus,
            String geometryStatus,
            String productType,
            String detectorId,
            long savedAtEpochMs,
            boolean hasHeatmap,
            int heatmapWidth,
            int heatmapHeight
    ) {
    }

    private final FrameArchiveConfig config;
    private final FrameArchiveSettingsStore settingsStore;
    private final ThreadPoolExecutor executor;
    private final FrameArchiveWriter writer;

    private FrameArchiveService(FrameArchiveConfig config, FrameArchiveSettingsStore settingsStore, ThreadPoolExecutor executor) {
        this.config = config;
        this.settingsStore = settingsStore;
        this.executor = executor;
        this.writer = new FrameArchiveWriter(config.directory(), this::maxFramesPerCamera);
    }

    public static FrameArchiveService open(FrameArchiveConfig config) throws IOException {
        if (config == null || !config.enabled()) {
            return null;
        }
        Files.createDirectories(config.directory());
        FrameArchiveSettingsStore settingsStore = FrameArchiveSettingsStore.open(
                config.directory(),
                config.defaultMaxFramesPerCamera(),
                config.maxAllowedFramesPerCamera()
        );
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                2,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "frame-archive");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        LOG.info(
                "frame archive enabled directory={} max_frames_per_camera={}",
                config.directory(),
                settingsStore.maxFramesPerCamera()
        );
        return new FrameArchiveService(config, settingsStore, executor);
    }

    public boolean enabled() {
        return config != null && config.enabled();
    }

    public Path directory() {
        return config.directory();
    }

    public int maxFramesPerCamera() {
        return settingsStore.maxFramesPerCamera();
    }

    public int maxAllowedFramesPerCamera() {
        return config.maxAllowedFramesPerCamera();
    }

    public void setMaxFramesPerCamera(int value) throws IOException {
        settingsStore.setMaxFramesPerCamera(value);
        int applied = settingsStore.maxFramesPerCamera();
        FrameArchiveRetention.trimAllCameras(config.directory(), applied);
        LOG.info("frame archive max_frames_per_camera set to {}", applied);
    }

    public void scheduleSave(SaveRequest request) {
        FrameArchiveSaveSupport.scheduleSave(request, enabled(), maxFramesPerCamera(), writer, executor, LOG);
    }

    /**
     * Snapshot + write immediately on the caller thread. Safe to call before ephemeral UI files are deleted.
     * Does not block the inspection pipeline (runs on the UI publish worker).
     */
    public boolean saveImmediately(SaveRequest request) {
        return FrameArchiveSaveSupport.saveImmediately(
                request, enabled(), maxFramesPerCamera(), config.directory(), writer, LOG);
    }

    public List<ArchivedFrame> listHistory(int cameraId) throws IOException {
        if (!enabled()) {
            return List.of();
        }
        return FrameArchiveIndex.listHistory(config.directory(), cameraId, maxFramesPerCamera());
    }

    public boolean deleteFrame(int cameraId, long frameId) {
        if (!enabled()) {
            return false;
        }
        Path frameDir = FrameArchivePaths.frameDirectory(config.directory(), cameraId, frameId);
        if (!Files.isDirectory(frameDir)) {
            return false;
        }
        FrameArchivePaths.deleteFrameDirectory(frameDir);
        return !Files.exists(frameDir);
    }

    public int clearCamera(int cameraId) throws IOException {
        if (!enabled()) {
            return 0;
        }
        return FrameArchiveRetention.clearCamera(config.directory(), cameraId);
    }

    public int clearAll() throws IOException {
        if (!enabled()) {
            return 0;
        }
        return FrameArchiveRetention.clearAll(config.directory());
    }

    public Optional<Path> resolveArtifact(int cameraId, long frameId, String artifactName) {
        if (!enabled() || artifactName == null || artifactName.isBlank()) {
            return Optional.empty();
        }
        Path artifact = FrameArchivePaths.frameDirectory(config.directory(), cameraId, frameId)
                .resolve(FrameArchivePaths.sanitizeArtifactName(artifactName));
        return Files.isRegularFile(artifact) ? Optional.of(artifact) : Optional.empty();
    }

    public String frameArtifactHttpPath(int cameraId, long frameId, String artifactName) {
        return FrameArchivePaths.frameArtifactHttpPath(cameraId, frameId, artifactName);
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }
}
