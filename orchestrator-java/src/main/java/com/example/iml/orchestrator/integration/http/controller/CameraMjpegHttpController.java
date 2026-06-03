package com.example.iml.orchestrator.integration.http.controller;

import com.example.iml.orchestrator.integration.http.HttpController;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.stream.CameraStreamServiceHolder;
import com.example.iml.orchestrator.integration.stream.MjpegStreamHub;
import com.example.iml.orchestrator.integration.ui.CameraPreviewStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * {@code GET /api/camera/{id}/stream.mjpeg} — MJPEG пока активен клиентский стрим (WS {@code stream_start}).
 */
public final class CameraMjpegHttpController implements HttpController {

    private final CameraStreamServiceHolder streamHolder;
    private final CameraPreviewStore previewStore;

    public CameraMjpegHttpController(CameraStreamServiceHolder streamHolder, CameraPreviewStore previewStore) {
        this.streamHolder = streamHolder;
        this.previewStore = previewStore;
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
        byte[] bootstrap = readBootstrapJpeg(cameraId);
        try (OutputStream os = ex.getResponseBody()) {
            streams.mjpegHub().serve(cameraId, os, bootstrap, null);
        } catch (IOException e) {
            // клиент отключился
        }
    }

    private byte[] readBootstrapJpeg(int cameraId) {
        if (previewStore == null) {
            return null;
        }
        return previewStore.latest(cameraId)
                .map(CameraPreviewStore.Latest::currentJpeg)
                .filter(path -> path != null && Files.isRegularFile(path))
                .map(path -> {
                    try {
                        return Files.readAllBytes(path);
                    } catch (IOException e) {
                        return null;
                    }
                })
                .orElse(null);
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
