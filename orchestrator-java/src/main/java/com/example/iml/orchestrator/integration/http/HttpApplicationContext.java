package com.example.iml.orchestrator.integration.http;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.config.PythonDetectorConfig;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.ui.CameraPreviewStore;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;

/**
 * Зависимости HTTP-слоя (DI для контроллеров).
 */
public record HttpApplicationContext(
        CameraPreviewStore cameraPreviewStore,
        GeometrySnapshotCache geometrySnapshotCache,
        ClientApiMount clientApi,
        LightTriggerClient lightTriggerClient,
        String analisSurfaceBaseUrl
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

    public static HttpApplicationContext of(
            CameraPreviewStore previewStore,
            GeometrySnapshotCache geometryCache,
            ClientApiMount clientApi,
            LightTriggerClient lightClient,
            java.util.Map<String, Object> rootYaml
    ) {
        PythonDetectorConfig py = PythonDetectorConfig.fromRootYaml(rootYaml);
        String base = py.configured() ? py.baseUrl() : "";
        return new HttpApplicationContext(previewStore, geometryCache, clientApi, lightClient, base);
    }
}
