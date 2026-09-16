package com.example.iml.orchestrator.integration.http;



import com.example.iml.orchestrator.integration.http.controller.CameraMjpegHttpController;
import com.example.iml.orchestrator.integration.http.controller.CameraPreviewHttpController;
import com.example.iml.orchestrator.integration.camera.CameraSettingsService;
import com.example.iml.orchestrator.integration.http.controller.CameraSettingsHttpController;

import com.example.iml.orchestrator.integration.http.controller.ClientApiHttpController;

import com.example.iml.orchestrator.integration.http.controller.FrameArchiveHttpController;

import com.example.iml.orchestrator.integration.http.controller.GeometryHttpController;

import com.example.iml.orchestrator.integration.http.controller.HealthHttpController;

import com.example.iml.orchestrator.integration.http.controller.LightHttpController;

import com.example.iml.orchestrator.integration.http.controller.OrchestratorAnalysisSettingsHttpController;

import com.example.iml.orchestrator.integration.http.controller.OrchestratorFpZonesHttpController;
import com.example.iml.orchestrator.integration.http.controller.OrchestratorLearningHttpController;

import com.sun.net.httpserver.HttpExchange;



import java.io.IOException;

import java.util.Optional;

import java.util.regex.Pattern;
import java.util.LinkedHashMap;



public final class HttpFrontController {



    private final HttpRouter router;



    public HttpFrontController(HttpApplicationContext ctx) {

        this.router = buildRouter(ctx);

    }



    public void dispatch(HttpExchange exchange) throws IOException {

        HttpRequestContext req = new HttpRequestContext(exchange);

        String method = req.method();

        String path = req.path();



        if ("OPTIONS".equalsIgnoreCase(method) && path.startsWith("/api/")) {

            HttpResponses.corsPreflight(exchange, "GET, POST, PUT, PATCH, DELETE, OPTIONS");

            return;

        }



        Optional<HttpController> handler = router.match(method, path);

        if (handler.isPresent()) {

            try {

                handler.get().handle(req);

            } catch (Exception e) {

                if (!req.exchange().getResponseHeaders().containsKey("Content-type")) {

                    HttpResponses.sendJsonError(req, 500, e.getClass().getSimpleName() + ": " + e.getMessage());

                }

            }

            return;

        }

        HttpResponses.notFound(req);

    }



    private static HttpRouter buildRouter(HttpApplicationContext ctx) {

        HttpRouter router = new HttpRouter();

        router.register(HttpRoute.exact("GET", "/health", new HealthHttpController()));



        CameraPreviewHttpController camera = new CameraPreviewHttpController(
                ctx.cameraPreviewStore(),
                ctx.configuredCameraIds(),
                ctx.analysisProfileByCamera()
        );

        router.register(HttpRoute.exact("GET", "/api/cameras", camera::listCameras));
        router.register(HttpRoute.exact("GET", "/api/inspection-layout", req -> {
            var groups = ctx.inspectionBucketGroups().stream().map(group -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("phase_id", group.phaseId());
                item.put("group_id", group.id());
                item.put("camera_ids", group.cameraIds());
                return item;
            }).toList();
            HttpResponses.sendJson(req, 200, java.util.Map.of("groups", groups));
        }));

        if (ctx.cameraStreamEnabled()) {
            CameraMjpegHttpController mjpeg = new CameraMjpegHttpController(
                    ctx.cameraStreamHolder(),
                    ctx.cameraPreviewStore()
            );
            router.register(HttpRoute.regex("GET", Pattern.compile("^/api/camera/\\d+/stream\\.mjpeg$"), mjpeg));
        }

        CameraSettingsHttpController cameraSettings = new CameraSettingsHttpController(
                new CameraSettingsService(
                        ctx.cameraWorkersHolder(),
                        ctx.cameraStreamHolder(),
                        ctx.cameraSettingsStore()
                )
        );
        router.register(HttpRoute.regex("GET", Pattern.compile("^/api/camera/\\d+/settings$"), cameraSettings));
        router.register(HttpRoute.regex("PATCH", Pattern.compile("^/api/camera/\\d+/settings$"), cameraSettings));
        router.register(HttpRoute.regex("PUT", Pattern.compile("^/api/camera/\\d+/settings$"), cameraSettings));

        router.register(HttpRoute.prefix("GET", "/api/camera/", camera::handleCameraPath));

        router.register(HttpRoute.prefix("GET", "/api/heatmap-artifact/", camera::handleHeatmapArtifact));
        router.register(HttpRoute.prefix("GET", "/api/inspection-artifacts/", camera::handleInspectionArtifact));

        if (ctx.geometryEnabled()) {

            GeometryHttpController geometry = new GeometryHttpController(ctx.geometrySnapshotCache());

            router.register(HttpRoute.exact("GET", "/api/geometry/cameras", geometry::listCameras));

            router.register(HttpRoute.prefix("GET", "/api/geometry/camera/", geometry::handleCameraPath));

        }



        if (ctx.frameArchiveEnabled()) {
            FrameArchiveHttpController frameArchive = new FrameArchiveHttpController(ctx.frameArchiveService());
            router.register(HttpRoute.exact("GET", "/api/client/frame-archive", frameArchive));
            router.register(HttpRoute.exact("PUT", "/api/client/frame-archive", frameArchive));
            router.register(HttpRoute.exact("DELETE", "/api/client/frame-archive", frameArchive));
            router.register(HttpRoute.exact("DELETE", "/api/frame-archive", frameArchive));
            router.register(HttpRoute.prefix("GET", "/api/frame-archive/", frameArchive));
            router.register(HttpRoute.prefix("DELETE", "/api/frame-archive/", frameArchive));
        }

        if (ctx.clientApiEnabled()) {

            ClientApiHttpController client = new ClientApiHttpController(ctx.clientApi());

            router.register(HttpRoute.prefix("*", "/api/client/", client::handleClientApi));

        }



        if (ctx.lightEnabled()) {

            LightHttpController light = new LightHttpController(
                    ctx.lightTriggerClient(),
                    ctx.lightServersConfig(),
                    ctx.lightBrightnessStore()
            );

            router.register(HttpRoute.exact("GET", "/api/orchestrator/light/brightness", light::handleBrightness));

            router.register(HttpRoute.exact("PUT", "/api/orchestrator/light/brightness", light::handleBrightness));

            router.register(HttpRoute.exact("POST", "/api/orchestrator/light/brightness", light::handleBrightness));

            router.register(HttpRoute.exact("GET", "/api/orchestrator/light/mode", light::handleMode));

            router.register(HttpRoute.exact("PUT", "/api/orchestrator/light/mode", light::handleMode));

        }

        OrchestratorFpZonesHttpController fpZones = new OrchestratorFpZonesHttpController(ctx.analisSurfaceBaseUrl());

        router.register(HttpRoute.regex("*", Pattern.compile("^/api/orchestrator/fp-zones(/.*)?$"), fpZones));

        OrchestratorLearningHttpController learning = new OrchestratorLearningHttpController(ctx.analisSurfaceBaseUrl());

        router.register(HttpRoute.regex("*", Pattern.compile("^/api/orchestrator/learning(/.*)?$"), learning));

        OrchestratorAnalysisSettingsHttpController analysisSettings = new OrchestratorAnalysisSettingsHttpController(
                ctx.analisSurfaceBaseUrl(),
                ctx.analysisProfileByCamera()
        );

        router.register(HttpRoute.regex(
                "*",
                Pattern.compile("^/api/orchestrator/analysis-settings(/.*)?$"),
                analysisSettings
        ));



        return router;

    }

}

