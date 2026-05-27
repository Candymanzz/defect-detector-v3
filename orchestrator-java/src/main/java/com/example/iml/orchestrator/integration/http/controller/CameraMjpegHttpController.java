package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.stream.CameraStreamServiceHolder;
import com.example.iml.orchestrator.integration.stream.MjpegStreamHub;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;

/**
 * {@code GET /api/camera/{id}/stream.mjpeg} — MJPEG пока активен клиентский стрим (WS {@code stream_start}).
 */
public final class CameraMjpegHttpController implements HttpController {

    private final CameraStreamServiceHolder streamHolder;

    public CameraMjpegHttpController(CameraStreamServiceHolder streamHolder) {
        this.streamHolder = streamHolder;
    }

    @Override
    public void handle(HttpRequestContext ctx) throws IOException {
        if (!"GET".equalsIgnoreCase(ctx.method())) {
            HttpResponses.methodNotAllowed(ctx);
            return;
        }
        String uri = ctx.path();
        if (!uri.endsWith("/stream.mjpeg")) {
            HttpResponses.notFound(ctx);
            return;
        }
        int cameraId = parseCameraId(uri);
        if (cameraId < 0) {
            HttpResponses.notFound(ctx);
            return;
        }
        CameraStreamService streams = streamHolder.get();
        if (streams == null) {
            HttpResponses.sendJsonError(ctx, 503, "client stream not configured");
            return;
        }
        if (!streams.isStreaming(cameraId)) {
            HttpResponses.sendJsonError(ctx, 503, "stream not active; send client.stream_start first");
            return;
        }
        HttpExchange ex = ctx.exchange();
        HttpResponses.corsJson(ex);
        ex.getResponseHeaders().set("Content-Type", MjpegStreamHub.contentType());
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        ex.getResponseHeaders().set("Connection", "close");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            streams.mjpegHub().serve(cameraId, os, null);
        } catch (IOException e) {
            // клиент отключился
        }
    }

    private static int parseCameraId(String uri) {
        try {
            String[] parts = uri.split("/");
            if (parts.length < 5) {
                return -1;
            }
            return Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
