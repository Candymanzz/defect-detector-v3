package com.example.iml.orchestrator.integration.camera;

import java.util.Map;

/**
 * Отложенная привязка {@link WorkerProcessSupervisor} к HTTP (UI-сервер поднимается раньше bootstrap).
 */
public final class CameraWorkersHolder {

    private volatile Map<Integer, WorkerProcessSupervisor> workers = Map.of();

    public WorkerProcessSupervisor get(int cameraId) {
        Map<Integer, WorkerProcessSupervisor> current = workers;
        return current == null ? null : current.get(cameraId);
    }

    public void set(Map<Integer, WorkerProcessSupervisor> workers) {
        this.workers = workers == null ? Map.of() : Map.copyOf(workers);
    }
}
