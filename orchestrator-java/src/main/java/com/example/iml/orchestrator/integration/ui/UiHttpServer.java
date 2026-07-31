package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.ui.http.InMemoryCameraPreviewStore;
import com.example.iml.orchestrator.integration.ui.http.UiHttpRuntimeAttachments;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Локальный HTTP для превью current/heatmap и (при наличии {@link GeometrySnapshotCache}) geometry.
 * Маршрутизация — {@link com.example.iml.orchestrator.integration.http.HttpFrontController} (паттерн Front Controller).
 */
public final class UiHttpServer implements AutoCloseable, CameraPreviewStore {

    public record ClientPreviewArtifact(Path path, int width, int height, String error) {
        public static ClientPreviewArtifact ok(Path path, int width, int height) {
            return new ClientPreviewArtifact(path, width, height, null);
        }

        public static ClientPreviewArtifact failed(String error) {
            return new ClientPreviewArtifact(null, 0, 0, error);
        }
    }

    public record InspectionPreviewArtifacts(ClientPreviewArtifact frame, ClientPreviewArtifact card) {
    }

    private final HttpServer httpServer;
    private final InMemoryCameraPreviewStore previewStore;
    private final UiHttpRuntimeAttachments runtimeAttachments;

    public UiHttpServer(String host, int port) throws IOException {
        this(host, port, null, ClientApiMount.disabled(), null, Map.of(), null, null, null);
    }

    public UiHttpServer(String host, int port, GeometrySnapshotCache geometrySnapshotCache) throws IOException {
        this(host, port, geometrySnapshotCache, ClientApiMount.disabled(), null, Map.of(), null, null, null);
    }

    public UiHttpServer(String host, int port, GeometrySnapshotCache geometrySnapshotCache, ClientApiMount clientApi)
            throws IOException {
        this(host, port, geometrySnapshotCache, clientApi, null, Map.of(), null, null, null);
    }

    public UiHttpServer(
            String host, int port, GeometrySnapshotCache geometrySnapshotCache, ClientApiMount clientApi,
            LightTriggerClient lightClient, Map<String, Object> rootYaml
    ) throws IOException {
        this(host, port, geometrySnapshotCache, clientApi, lightClient, rootYaml, null, null, null);
    }

    public UiHttpServer(
            String host, int port, GeometrySnapshotCache geometrySnapshotCache, ClientApiMount clientApi,
            LightTriggerClient lightClient, Map<String, Object> rootYaml, CameraSettingsStore cameraSettingsStore
    ) throws IOException {
        this(host, port, geometrySnapshotCache, clientApi, lightClient, rootYaml, cameraSettingsStore, null, null);
    }

    public UiHttpServer(
            String host, int port, GeometrySnapshotCache geometrySnapshotCache, ClientApiMount clientApi,
            LightTriggerClient lightClient, Map<String, Object> rootYaml, CameraSettingsStore cameraSettingsStore,
            LightBrightnessStore lightBrightnessStore
    ) throws IOException {
        this(host, port, geometrySnapshotCache, clientApi, lightClient, rootYaml, cameraSettingsStore, lightBrightnessStore, null);
    }

    public UiHttpServer(
            String host, int port, GeometrySnapshotCache geometrySnapshotCache, ClientApiMount clientApi,
            LightTriggerClient lightClient, Map<String, Object> rootYaml, CameraSettingsStore cameraSettingsStore,
            LightBrightnessStore lightBrightnessStore, FrameArchiveService frameArchiveService
    ) throws IOException {
        UiHttpServerFactory.Started started = UiHttpServerFactory.start(
                host, port, geometrySnapshotCache, clientApi, lightClient, rootYaml,
                cameraSettingsStore, lightBrightnessStore, frameArchiveService
        );
        this.httpServer = started.httpServer();
        this.previewStore = started.previewStore();
        this.runtimeAttachments = started.runtimeAttachments();
    }

    @Override
    public Optional<Latest> latest(int cameraId) {
        return previewStore.latest(cameraId);
    }

    @Override
    public Map<Integer, Latest> latestByCamera() {
        return previewStore.latestByCamera();
    }

    @Override
    public String registerHeatmapArtifact(int cameraId, Path heatmapU8Path) {
        return previewStore.registerHeatmapArtifact(cameraId, heatmapU8Path);
    }

    @Override
    public Path resolveHeatmapArtifactPath(String token) {
        return previewStore.resolveHeatmapArtifactPath(token);
    }

    @Override
    public RegisteredInspectionArtifacts registerInspectionArtifacts(
            int cameraId, long frameId, Path frameJpeg, Path cardJpeg, Path heatmapU8
    ) throws IOException {
        return previewStore.registerInspectionArtifacts(cameraId, frameId, frameJpeg, cardJpeg, heatmapU8);
    }

    public RegisteredInspectionArtifacts attachInspectionHeatmap(String bundleId, Path heatmapU8) throws IOException {
        return previewStore.attachInspectionHeatmap(bundleId, heatmapU8);
    }

    @Override
    public byte[] readInspectionArtifact(String bundleId, String artifactName) throws IOException {
        return previewStore.readInspectionArtifact(bundleId, artifactName);
    }

    @Override
    public void update(
            int cameraId, long frameId, String productType, String detectorId, String shmName,
            int captureWidth, int captureHeight, Path currentJpeg, int currentJpegW, int currentJpegH,
            Path heatmapU8, int heatmapU8W, int heatmapU8H, InspectionDecision decision
    ) {
        previewStore.update(
                cameraId, frameId, productType, detectorId, shmName,
                captureWidth, captureHeight, currentJpeg, currentJpegW, currentJpegH,
                heatmapU8, heatmapU8W, heatmapU8H, decision
        );
    }

    public void attachCameraStreamService(CameraStreamService cameraStreamService) {
        runtimeAttachments.attachCameraStreamService(cameraStreamService);
    }

    public void attachCameraWorkers(Map<Integer, WorkerProcessSupervisor> workersByCamera) {
        runtimeAttachments.attachCameraWorkers(workersByCamera);
    }

    @Override
    public void close() {
        httpServer.stop(0);
    }

    public static ClientPreviewArtifact writeCurrentJpegFromBgrShm(
            String shmName, int width, int height, int stride, int previewMaxWidth, float quality
    ) {
        return UiHttpPreviewArtifacts.writeCurrent(shmName, width, height, stride, previewMaxWidth, quality);
    }

    public static ClientPreviewArtifact writeCurrentJpegFromBgrShm(
            String shmName, int width, int height, int stride, int previewMaxWidth, float quality, int cameraId
    ) {
        return UiHttpPreviewArtifacts.writeCurrent(shmName, width, height, stride, previewMaxWidth, quality, cameraId);
    }

    public static ClientPreviewArtifact writeCurrentJpegFromBgrShm(
            String shmName, int width, int height, int stride, long shmOffset,
            int previewMaxWidth, float quality, int cameraId
    ) {
        return UiHttpPreviewArtifacts.writeCurrent(
                shmName, width, height, stride, shmOffset, previewMaxWidth, quality, cameraId);
    }

    public static InspectionPreviewArtifacts writeInspectionJpegsFromBgrShm(
            String shmName, int width, int height, int stride, long shmOffset,
            int frameMaxWidth, float frameQuality, int cardMaxWidth, float cardQuality
    ) {
        return UiHttpPreviewArtifacts.writeInspection(
                shmName, width, height, stride, shmOffset,
                frameMaxWidth, frameQuality, cardMaxWidth, cardQuality);
    }
}
