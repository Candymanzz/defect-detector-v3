package com.example.iml.orchestrator.integration.bootstrap.context.impl;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.port.AbstractCameraRuntimeHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.LivePreviewHost;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.preview.LivePreviewGate;
import com.example.iml.orchestrator.integration.preview.LivePreviewPublisher;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;

import java.util.List;
import java.util.Map;

/** Адаптер: live preview. */
public final class LivePreviewHostImpl extends AbstractCameraRuntimeHost implements LivePreviewHost {

    public LivePreviewHostImpl(CameraRuntimeContext runtime) {
        super(runtime);
    }

    @Override
    public Map<String, Object> root() {
        return env().root();
    }

    @Override
    public Map<String, Object> integration() {
        return preflight().integration();
    }

    @Override
    public List<Map<String, Object>> activeCameras() {
        return workers().activeCameras();
    }

    @Override
    public Map<Integer, WorkerProcessSupervisor> workersByCamera() {
        return workers().workersByCamera();
    }

    @Override
    public LightTriggerClient lightClient() {
        return pipeline().lightClient();
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
    public int flashLeadMs() {
        return pipeline().flashLeadMs();
    }

    @Override
    public Map<String, Object> uiCfg() {
        return processes().uiCfg();
    }

    @Override
    public IntegrationBootConfig bootConfig() {
        return preflight().bootConfig();
    }

    @Override
    public PipelineReferenceRegistry pipelineReferenceRegistry() {
        return pipeline().pipelineReferenceRegistry();
    }

    @Override
    public CameraStreamService cameraStreamService() {
        return workers().cameraStreamService();
    }

    @Override
    public LivePreviewGate livePreviewGate() {
        return preview().livePreviewGate();
    }

    @Override
    public PerCameraInspectionGate inspectionGate() {
        return processes().inspectionGate();
    }

    @Override
    public LineSynchronizedCaptureCoordinator lineCaptureCoordinator() {
        return triggers().lineCaptureCoordinator();
    }

    @Override
    public void setLivePreview(LivePreviewPublisher livePreview) {
        preview().setLivePreview(livePreview);
    }
}
