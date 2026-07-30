package com.example.iml.orchestrator.integration.bootstrap.context.impl;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.port.AbstractCameraRuntimeHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraWorkerHost;
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

/** Адаптер: старт camera-worker / stream. */
public final class CameraWorkerHostImpl extends AbstractCameraRuntimeHost implements CameraWorkerHost {

    public CameraWorkerHostImpl(CameraRuntimeContext runtime) {
        super(runtime);
    }

    @Override
    public Map<String, Object> root() {
        return env().root();
    }

    @Override
    public Path projectRoot() {
        return env().projectRoot();
    }

    @Override
    public Map<String, Object> integration() {
        return preflight().integration();
    }

    @Override
    public IntegrationBootConfig bootConfig() {
        return preflight().bootConfig();
    }

    @Override
    public List<Map<String, Object>> cameras() {
        return preflight().cameras();
    }

    @Override
    public Path workerBin() {
        return preflight().workerBin();
    }

    @Override
    public Path workerConfigPath() {
        return preflight().workerConfigPath();
    }

    @Override
    public CameraSettingsStore cameraSettingsStore() {
        return ui().cameraSettingsStore();
    }

    @Override
    public UiHttpServer uiServer() {
        return ui().uiServer();
    }

    @Override
    public ClientWebSocketServer clientWsServer() {
        return ui().clientWsServer();
    }

    @Override
    public Map<String, Object> uiCfg() {
        return processes().uiCfg();
    }

    @Override
    public Map<Integer, String> detectorByCamera() {
        return pipeline().detectorByCamera();
    }

    @Override
    public WorkerCaptureCoordinator captureCoordinator() {
        return processes().captureCoordinator();
    }

    @Override
    public LivePreviewGate livePreviewGate() {
        return preview().livePreviewGate();
    }

    @Override
    public Map<Integer, WorkerProcessSupervisor> workersByCamera() {
        return workers().workersByCamera();
    }

    @Override
    public void setWorkersByCamera(Map<Integer, WorkerProcessSupervisor> workersByCamera) {
        workers().setWorkersByCamera(workersByCamera);
    }

    @Override
    public List<Map<String, Object>> activeCameras() {
        return workers().activeCameras();
    }

    @Override
    public void setActiveCameras(List<Map<String, Object>> activeCameras) {
        workers().setActiveCameras(activeCameras);
    }

    @Override
    public void setCameraStreamService(CameraStreamService cameraStreamService) {
        workers().setCameraStreamService(cameraStreamService);
    }
}
