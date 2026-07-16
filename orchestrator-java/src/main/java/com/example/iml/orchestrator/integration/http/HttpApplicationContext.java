package com.example.iml.orchestrator.integration.http;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.config.PythonDetectorConfig;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.camera.CameraWorkersHolder;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.stream.CameraStreamServiceHolder;
import com.example.iml.orchestrator.integration.ui.CameraPreviewStore;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;

/**
 * Зависимости HTTP-слоя (DI для контроллеров).
 */
public record HttpApplicationContext(
        CameraPreviewStore cameraPreviewStore,
        GeometrySnapshotCache geometrySnapshotCache,
        ClientApiMount clientApi,
        LightTriggerClient lightTriggerClient,
        LightServersConfig lightServersConfig,
        LightBrightnessStore lightBrightnessStore,
        String analisSurfaceBaseUrl,
        CameraStreamServiceHolder cameraStreamHolder,
        CameraWorkersHolder cameraWorkersHolder,
        CameraSettingsStore cameraSettingsStore,
        FrameArchiveService frameArchiveService,
        java.util.List<Integer> configuredCameraIds,
        java.util.Map<Integer, String> analysisProfileByCamera
) {
    public boolean geometryEnabled() {
        return geometrySnapshotCache != null;
    }

    public boolean clientApiEnabled() {
        return clientApi != null && clientApi.enabled();
    }

    public boolean lightEnabled() {
        return lightTriggerClient != null;
    }

    public boolean cameraStreamEnabled() {
        return cameraStreamHolder != null;
    }

    public boolean frameArchiveEnabled() {
        return frameArchiveService != null && frameArchiveService.enabled();
    }

    public LightServersConfig lightServersConfig() {
        return lightServersConfig;
    }

    public static HttpApplicationContext of(
            CameraPreviewStore previewStore,
            GeometrySnapshotCache geometryCache,
            ClientApiMount clientApi,
            LightTriggerClient lightClient,
            java.util.Map<String, Object> rootYaml
    ) {
        return of(previewStore, geometryCache, clientApi, lightClient, rootYaml, null, null, null);
    }

    public static HttpApplicationContext of(
            CameraPreviewStore previewStore,
            GeometrySnapshotCache geometryCache,
            ClientApiMount clientApi,
            LightTriggerClient lightClient,
            java.util.Map<String, Object> rootYaml,
            CameraSettingsStore cameraSettingsStore
    ) {
        return of(previewStore, geometryCache, clientApi, lightClient, rootYaml, cameraSettingsStore, null, null);
    }

    public static HttpApplicationContext of(
            CameraPreviewStore previewStore,
            GeometrySnapshotCache geometryCache,
            ClientApiMount clientApi,
            LightTriggerClient lightClient,
            java.util.Map<String, Object> rootYaml,
            CameraSettingsStore cameraSettingsStore,
            LightBrightnessStore lightBrightnessStore
    ) {
        return of(previewStore, geometryCache, clientApi, lightClient, rootYaml, cameraSettingsStore, lightBrightnessStore, null);
    }

    public static HttpApplicationContext of(
            CameraPreviewStore previewStore,
            GeometrySnapshotCache geometryCache,
            ClientApiMount clientApi,
            LightTriggerClient lightClient,
            java.util.Map<String, Object> rootYaml,
            CameraSettingsStore cameraSettingsStore,
            LightBrightnessStore lightBrightnessStore,
            FrameArchiveService frameArchiveService
    ) {
        PythonDetectorConfig py = PythonDetectorConfig.fromRootYaml(rootYaml);
        String base = py.configured() ? py.baseUrl() : "";
        LightServersConfig lightCfg = LightServersConfig.fromRootYaml(rootYaml);
        return new HttpApplicationContext(
                previewStore,
                geometryCache,
                clientApi,
                lightClient,
                lightCfg,
                lightBrightnessStore,
                base,
                new CameraStreamServiceHolder(),
                new CameraWorkersHolder(),
                cameraSettingsStore,
                frameArchiveService,
                ConfiguredCameras.enabledIds(rootYaml),
                ConfiguredCameras.analysisProfileByCameraId(rootYaml)
        );
    }
}
