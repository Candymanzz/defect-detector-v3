package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;

import java.util.List;
import java.util.Map;

/** Sink for objects created during camera-worker bootstrap. */
public interface CameraWorkerSink {

    void setWorkersByCamera(Map<Integer, WorkerProcessSupervisor> workersByCamera);

    void setActiveCameras(List<Map<String, Object>> activeCameras);

    void setCameraStreamService(CameraStreamService cameraStreamService);
}
