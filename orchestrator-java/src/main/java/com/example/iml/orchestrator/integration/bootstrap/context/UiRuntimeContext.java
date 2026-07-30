package com.example.iml.orchestrator.integration.bootstrap.context;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Результат UI HTTP / client WS / frame archive.
 */
public final class UiRuntimeContext {

    private final PipelineAssemblyContext pipeline;

    private CameraSettingsStore cameraSettingsStore;
    private FrameArchiveService frameArchiveService;
    private UiHttpServer uiServer;
    private ClientWebSocketServer clientWsServer;
    private BinaryRpcSupervisor uiVisualsPython;
    private ExecutorService uiArtifactsExecutor;

    public UiRuntimeContext(PipelineAssemblyContext pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    public PipelineAssemblyContext pipeline() {
        return pipeline;
    }

    public ChildProcessesContext processes() {
        return pipeline.processes();
    }

    public PreflightContext preflight() {
        return pipeline.preflight();
    }

    public BootstrapEnvironment env() {
        return pipeline.env();
    }

    public CameraSettingsStore cameraSettingsStore() {
        return cameraSettingsStore;
    }

    public void setCameraSettingsStore(CameraSettingsStore cameraSettingsStore) {
        this.cameraSettingsStore = cameraSettingsStore;
    }

    public FrameArchiveService frameArchiveService() {
        return frameArchiveService;
    }

    public void setFrameArchiveService(FrameArchiveService frameArchiveService) {
        this.frameArchiveService = frameArchiveService;
    }

    public UiHttpServer uiServer() {
        return uiServer;
    }

    public void setUiServer(UiHttpServer uiServer) {
        this.uiServer = uiServer;
    }

    public ClientWebSocketServer clientWsServer() {
        return clientWsServer;
    }

    public void setClientWsServer(ClientWebSocketServer clientWsServer) {
        this.clientWsServer = clientWsServer;
    }

    public BinaryRpcSupervisor uiVisualsPython() {
        return uiVisualsPython;
    }

    public void setUiVisualsPython(BinaryRpcSupervisor uiVisualsPython) {
        this.uiVisualsPython = uiVisualsPython;
    }

    public ExecutorService uiArtifactsExecutor() {
        return uiArtifactsExecutor;
    }

    public void setUiArtifactsExecutor(ExecutorService uiArtifactsExecutor) {
        this.uiArtifactsExecutor = uiArtifactsExecutor;
    }
}
