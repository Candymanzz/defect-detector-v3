package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.pipeline.stages.WorkerCaptureCoordinator;
import com.example.iml.orchestrator.integration.preview.LivePreviewGate;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Порт запуска camera-worker и client stream.
 */
public interface CameraWorkerHost {

    Map<String, Object> root();

    Path projectRoot();

    Map<String, Object> integration();

    IntegrationBootConfig bootConfig();

    List<Map<String, Object>> cameras();

    Path workerBin();

    Path workerConfigPath();

    CameraSettingsStore cameraSettingsStore();

    UiHttpServer uiServer();

    ClientWebSocketServer clientWsServer();

    Map<String, Object> uiCfg();

    Map<Integer, String> detectorByCamera();

    WorkerCaptureCoordinator captureCoordinator();

    LivePreviewGate livePreviewGate();

    Map<Integer, WorkerProcessSupervisor> workersByCamera();

    void setWorkersByCamera(Map<Integer, WorkerProcessSupervisor> workersByCamera);

    List<Map<String, Object>> activeCameras();

    void setActiveCameras(List<Map<String, Object>> activeCameras);

    void setCameraStreamService(CameraStreamService cameraStreamService);
}
