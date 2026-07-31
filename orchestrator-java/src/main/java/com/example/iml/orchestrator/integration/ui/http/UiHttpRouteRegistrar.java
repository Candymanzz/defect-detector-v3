package com.example.iml.orchestrator.integration.ui.http;

import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.http.HttpApplicationContext;
import com.example.iml.orchestrator.integration.http.HttpFrontController;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.openapi.OrchestratorApiDocumentationHandlers;
import com.example.iml.orchestrator.integration.ui.CameraPreviewStore;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Создание {@link HttpServer}, wiring {@link HttpFrontController} и старт слушателя.
 */
public final class UiHttpRouteRegistrar {
    private UiHttpRouteRegistrar() {
    }

    public record StartedHttp(HttpServer httpServer, HttpApplicationContext httpContext) {
    }

    public static StartedHttp createAndStart(
            String host,
            int port,
            CameraPreviewStore previewStore,
            GeometrySnapshotCache geometrySnapshotCache,
            ClientApiMount clientApi,
            LightTriggerClient lightClient,
            Map<String, Object> rootYaml,
            CameraSettingsStore cameraSettingsStore,
            LightBrightnessStore lightBrightnessStore,
            FrameArchiveService frameArchiveService
    ) throws IOException {
        InetSocketAddress addr = new InetSocketAddress(host, port);
        HttpServer httpServer = HttpServer.create(addr, 0);
        HttpApplicationContext httpContext = HttpApplicationContext.of(
                previewStore,
                geometrySnapshotCache,
                clientApi == null ? ClientApiMount.disabled() : clientApi,
                lightClient,
                rootYaml == null ? Map.of() : rootYaml,
                cameraSettingsStore,
                lightBrightnessStore,
                frameArchiveService
        );
        HttpFrontController frontController = new HttpFrontController(httpContext);
        OrchestratorApiDocumentationHandlers.register(httpServer);
        httpServer.createContext("/", exchange -> frontController.dispatch(exchange));
        httpServer.setExecutor(null);
        httpServer.start();
        return new StartedHttp(httpServer, httpContext);
    }
}
