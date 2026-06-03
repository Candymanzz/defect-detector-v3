package com.example.iml.orchestrator.integration.http;



import com.example.iml.orchestrator.integration.http.controller.CameraMjpegHttpController;
import com.example.iml.orchestrator.integration.http.controller.CameraPreviewHttpController;

import com.example.iml.orchestrator.integration.http.controller.ClientApiHttpController;

import com.example.iml.orchestrator.integration.http.controller.GeometryHttpController;

import com.example.iml.orchestrator.integration.http.controller.HealthHttpController;

import com.example.iml.orchestrator.integration.http.controller.InspectionTriggerHttpController;

import com.example.iml.orchestrator.integration.http.controller.LightHttpController;

import com.example.iml.orchestrator.integration.http.controller.OrchestratorFpZonesHttpController;

import com.sun.net.httpserver.HttpExchange;



import java.io.IOException;

import java.util.Optional;

import java.util.regex.Pattern;



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

            HttpResponses.corsPreflight(exchange, "GET, POST, PUT, DELETE, OPTIONS");

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
                ctx.configuredCameraIds()
        );

        router.register(HttpRoute.exact("GET", "/api/cameras", camera::listCameras));

        if (ctx.cameraStreamEnabled()) {
            CameraMjpegHttpController mjpeg = new CameraMjpegHttpController(ctx.cameraStreamHolder());
            router.register(HttpRoute.regex("GET", Pattern.compile("^/api/camera/\\d+/stream\\.mjpeg$"), mjpeg));
        }

        router.register(HttpRoute.prefix("GET", "/api/camera/", camera::handleCameraPath));

        router.register(HttpRoute.prefix("GET", "/api/heatmap-artifact/", camera::handleHeatmapArtifact));

        if (ctx.geometryEnabled()) {

            GeometryHttpController geometry = new GeometryHttpController(ctx.geometrySnapshotCache());

            router.register(HttpRoute.exact("GET", "/api/geometry/cameras", geometry::listCameras));

            router.register(HttpRoute.prefix("GET", "/api/geometry/camera/", geometry::handleCameraPath));

        }



        if (ctx.clientApiEnabled()) {

            ClientApiHttpController client = new ClientApiHttpController(ctx.clientApi());

            router.register(HttpRoute.prefix("*", "/api/client/", client::handleClientApi));

        }



        if (ctx.lightEnabled()) {

            LightHttpController light = new LightHttpController(ctx.lightTriggerClient(), ctx.lightServersConfig());

            router.register(HttpRoute.exact("GET", "/api/orchestrator/light/brightness", light::handleBrightness));

            router.register(HttpRoute.exact("PUT", "/api/orchestrator/light/brightness", light::handleBrightness));

            router.register(HttpRoute.exact("POST", "/api/orchestrator/light/brightness", light::handleBrightness));

        }

        if (ctx.inspectionTriggerEnabled()) {

            InspectionTriggerHttpController inspectTrigger = new InspectionTriggerHttpController(ctx.inspectionTriggerBusHolder());

            router.register(HttpRoute.exact("POST", "/api/orchestrator/inspect/trigger", inspectTrigger));

        }



        OrchestratorFpZonesHttpController fpZones = new OrchestratorFpZonesHttpController(ctx.analisSurfaceBaseUrl());

        router.register(HttpRoute.regex("*", Pattern.compile("^/api/orchestrator/fp-zones(/.*)?$"), fpZones));



        return router;

    }

}

