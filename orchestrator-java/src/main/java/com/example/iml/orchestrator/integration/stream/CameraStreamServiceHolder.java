package com.example.iml.orchestrator.integration.stream;

/**
 * Отложенная привязка {@link CameraStreamService} к HTTP (сервер UI поднимается раньше bootstrap).
 */
public final class CameraStreamServiceHolder {

    private volatile CameraStreamService service;

    public CameraStreamService get() {
        return service;
    }

    public void set(CameraStreamService service) {
        this.service = service;
    }
}
