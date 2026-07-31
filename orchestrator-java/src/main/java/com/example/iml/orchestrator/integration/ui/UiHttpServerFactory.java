package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.ui.http.InMemoryCameraPreviewStore;
import com.example.iml.orchestrator.integration.ui.http.UiHttpRouteRegistrar;
import com.example.iml.orchestrator.integration.ui.http.UiHttpRuntimeAttachments;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.util.Map;

/** Creates HTTP server + preview store bindings for {@link UiHttpServer}. */
final class UiHttpServerFactory {

    record Started(
            HttpServer httpServer,
            InMemoryCameraPreviewStore previewStore,
            UiHttpRuntimeAttachments runtimeAttachments
    ) {
    }

    private UiHttpServerFactory() {
    }

    static Started start(
            String host,
            int port,
            GeometrySnapshotCache geometrySnapshotCache,
            ClientApiMount clientApi,
            LightTriggerClient lightClient,
            Map<String, Object> rootYaml,
            CameraSettingsStore cameraSettingsStore,
            LightBrightnessStore lightBrightnessStore,
            FrameArchiveService frameArchiveService
    ) throws IOException {
        InMemoryCameraPreviewStore previewStore = new InMemoryCameraPreviewStore();
        UiHttpRouteRegistrar.StartedHttp started = UiHttpRouteRegistrar.createAndStart(
                host,
                port,
                previewStore,
                geometrySnapshotCache,
                clientApi,
                lightClient,
                rootYaml,
                cameraSettingsStore,
                lightBrightnessStore,
                frameArchiveService
        );
        return new Started(
                started.httpServer(),
                previewStore,
                new UiHttpRuntimeAttachments(started.httpContext())
        );
    }
}
