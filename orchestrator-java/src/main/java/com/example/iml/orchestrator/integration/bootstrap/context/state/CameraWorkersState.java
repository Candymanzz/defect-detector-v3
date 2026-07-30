package com.example.iml.orchestrator.integration.bootstrap.context.state;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Workers / stream / reference snapshots per camera. */
public final class CameraWorkersState {

    private List<Map<String, Object>> activeCameras = List.of();
    private Map<Integer, WorkerProcessSupervisor> workersByCamera = new LinkedHashMap<>();
    private CameraStreamService cameraStreamService;
    private final Map<Integer, ReferenceSnapshot> referenceByCamera = new ConcurrentHashMap<>();

    public List<Map<String, Object>> activeCameras() {
        return activeCameras;
    }

    public void setActiveCameras(List<Map<String, Object>> activeCameras) {
        this.activeCameras = activeCameras == null ? List.of() : activeCameras;
    }

    public Map<Integer, WorkerProcessSupervisor> workersByCamera() {
        return workersByCamera;
    }

    public void setWorkersByCamera(Map<Integer, WorkerProcessSupervisor> workersByCamera) {
        this.workersByCamera = workersByCamera == null ? new LinkedHashMap<>() : workersByCamera;
    }

    public CameraStreamService cameraStreamService() {
        return cameraStreamService;
    }

    public void setCameraStreamService(CameraStreamService cameraStreamService) {
        this.cameraStreamService = cameraStreamService;
    }

    public Map<Integer, ReferenceSnapshot> referenceByCamera() {
        return referenceByCamera;
    }
}
