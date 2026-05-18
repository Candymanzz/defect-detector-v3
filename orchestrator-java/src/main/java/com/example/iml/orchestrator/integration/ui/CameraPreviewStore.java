package com.example.iml.orchestrator.integration.ui;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Хранилище превью камер для HTTP-контроллеров.
 */
public interface CameraPreviewStore {

    record Latest(
            long frameId,
            String productType,
            String detectorId,
            String shmName,
            int captureWidth,
            int captureHeight,
            Path currentJpeg,
            int currentJpegWidth,
            int currentJpegHeight,
            Path heatmapU8,
            int heatmapU8Width,
            int heatmapU8Height,
            long updatedAtEpochMs
    ) {
    }

    Optional<Latest> latest(int cameraId);

    Map<Integer, Latest> latestByCamera();

    String registerHeatmapArtifact(int cameraId, Path heatmapU8Path);

    /** Путь к .u8 по opaque-токену или {@code null}. */
    Path resolveHeatmapArtifactPath(String token);

    void update(
            int cameraId,
            long frameId,
            String productType,
            String detectorId,
            String shmName,
            int captureWidth,
            int captureHeight,
            Path currentJpeg,
            int currentJpegW,
            int currentJpegH,
            Path heatmapU8,
            int heatmapU8W,
            int heatmapU8H
    );
}
