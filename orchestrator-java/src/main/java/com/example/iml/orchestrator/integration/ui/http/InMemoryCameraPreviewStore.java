package com.example.iml.orchestrator.integration.ui.http;

import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.ui.CameraPreviewStore;
import com.example.iml.orchestrator.integration.ui.HeatmapArtifactRegistry;
import com.example.iml.orchestrator.integration.ui.InspectionArtifactRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory latest preview + heatmap/inspection artifact registries для UI HTTP.
 */
public final class InMemoryCameraPreviewStore implements CameraPreviewStore {
    private static final Path INSPECTION_ARTIFACT_DIR = Path.of(
            System.getProperty("java.io.tmpdir"),
            "iml-ui-inspection-artifacts"
    );

    private final Map<Integer, Latest> latestByCamera = new ConcurrentHashMap<>();
    private final HeatmapArtifactRegistry heatmapArtifacts = new HeatmapArtifactRegistry();
    private final InspectionArtifactRegistry inspectionArtifacts =
            new InspectionArtifactRegistry(INSPECTION_ARTIFACT_DIR);

    @Override
    public Optional<Latest> latest(int cameraId) {
        return Optional.ofNullable(latestByCamera.get(cameraId));
    }

    @Override
    public Map<Integer, Latest> latestByCamera() {
        return Map.copyOf(latestByCamera);
    }

    @Override
    public String registerHeatmapArtifact(int cameraId, Path heatmapU8Path) {
        if (heatmapU8Path == null) {
            return null;
        }
        return heatmapArtifacts.register(cameraId, heatmapU8Path);
    }

    @Override
    public Path resolveHeatmapArtifactPath(String token) {
        return heatmapArtifacts.resolve(token);
    }

    @Override
    public RegisteredInspectionArtifacts registerInspectionArtifacts(
            int cameraId,
            long frameId,
            Path frameJpeg,
            Path cardJpeg,
            Path heatmapU8
    ) throws IOException {
        InspectionArtifactRegistry.Bundle bundle =
                inspectionArtifacts.register(cameraId, frameId, frameJpeg, cardJpeg, heatmapU8);
        return new RegisteredInspectionArtifacts(
                bundle.id(),
                bundle.frameJpeg(),
                bundle.cardJpeg(),
                bundle.heatmapU8()
        );
    }

    public RegisteredInspectionArtifacts attachInspectionHeatmap(String bundleId, Path heatmapU8) throws IOException {
        InspectionArtifactRegistry.Bundle bundle = inspectionArtifacts.attachHeatmap(bundleId, heatmapU8);
        return new RegisteredInspectionArtifacts(
                bundle.id(),
                bundle.frameJpeg(),
                bundle.cardJpeg(),
                bundle.heatmapU8()
        );
    }

    @Override
    public byte[] readInspectionArtifact(String bundleId, String artifactName) throws IOException {
        return inspectionArtifacts.read(bundleId, artifactName);
    }

    @Override
    public void update(
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
            int heatmapU8H,
            InspectionDecision decision
    ) {
        Boolean overallPass = null;
        String action = null;
        Double anomalyScore = null;
        String pythonStatus = null;
        String geometryStatus = null;
        if (decision != null) {
            overallPass = decision.overallPass();
            action = decision.action();
            anomalyScore = decision.anomalyScore();
            pythonStatus = decision.pythonStatus();
            geometryStatus = decision.geometryStatus();
        }
        latestByCamera.put(
                cameraId,
                new Latest(
                        frameId,
                        productType == null ? "" : productType,
                        detectorId == null ? "" : detectorId,
                        shmName == null ? "" : shmName,
                        captureWidth,
                        captureHeight,
                        currentJpeg,
                        currentJpegW,
                        currentJpegH,
                        heatmapU8,
                        heatmapU8W,
                        heatmapU8H,
                        overallPass,
                        action,
                        anomalyScore,
                        pythonStatus,
                        geometryStatus,
                        System.currentTimeMillis()
                )
        );
    }
}
