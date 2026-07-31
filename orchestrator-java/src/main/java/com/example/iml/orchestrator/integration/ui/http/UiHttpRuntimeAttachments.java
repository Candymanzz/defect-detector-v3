package com.example.iml.orchestrator.integration.ui.http;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.http.HttpApplicationContext;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;

import java.util.Map;

/**
 * Отложенная привязка camera stream / workers к уже поднятому HTTP-контексту.
 */
public final class UiHttpRuntimeAttachments {
    private final HttpApplicationContext httpContext;

    public UiHttpRuntimeAttachments(HttpApplicationContext httpContext) {
        this.httpContext = httpContext;
    }

    public void attachCameraStreamService(CameraStreamService cameraStreamService) {
        if (httpContext != null && httpContext.cameraStreamHolder() != null) {
            httpContext.cameraStreamHolder().set(cameraStreamService);
        }
    }

    public void attachCameraWorkers(Map<Integer, WorkerProcessSupervisor> workersByCamera) {
        if (httpContext != null && httpContext.cameraWorkersHolder() != null) {
            httpContext.cameraWorkersHolder().set(workersByCamera);
        }
    }
}
