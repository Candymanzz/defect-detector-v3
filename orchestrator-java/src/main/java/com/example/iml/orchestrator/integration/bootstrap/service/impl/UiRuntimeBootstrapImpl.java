package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.UiRuntimeBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.context.UiRuntimeContext;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.ui.FrameArchiveConfig;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import org.apache.logging.log4j.Logger;

/**
 * UI HTTP, client WebSocket, frame archive.
 */
public final class UiRuntimeBootstrapImpl extends AbstractBootstrapService implements UiRuntimeBootstrap {

    public UiRuntimeBootstrapImpl(Logger log) {
        super(log);
    }

    @Override
    public void bootstrap(UiRuntimeContext ui) {
        var env = ui.env();
        var processes = ui.processes();
        var pipeline = ui.pipeline();

        ui.setCameraSettingsStore(openOptionalStoreUnderProject(
                env.projectRoot(),
                "config/data/camera_runtime_settings.json",
                "camera settings store",
                CameraSettingsStore::open
        ));
        FrameArchiveService frameArchiveService = null;
        try {
            frameArchiveService = FrameArchiveService.open(FrameArchiveConfig.fromRootYaml(env.root(), env.windows()));
        } catch (Exception e) {
            log.warn("frame archive failed to start: {}", e.getMessage());
        }
        ui.setFrameArchiveService(frameArchiveService);

        ui.setUiServer(processes.uiSidecar().startHttpServerIfEnabled(
                processes.uiCfg(),
                processes.geometrySnapshotCache(),
                processes.clientApiMount(),
                pipeline.lightClient(),
                env.root(),
                ui.cameraSettingsStore(),
                pipeline.lightBrightnessStore(),
                frameArchiveService
        ));
        processes.uiSidecar().setFrameArchiveService(frameArchiveService);

        ClientWsConfig clientWsCfg = ClientWsConfig.fromRootYaml(env.root());
        ClientWebSocketServer clientWsServer = null;
        if (clientWsCfg.enabled()) {
            try {
                clientWsServer = new ClientWebSocketServer(log, clientWsCfg);
                clientWsServer.begin();
            } catch (Exception e) {
                log.warn("client_ws failed to start: {}", e.getMessage());
                clientWsServer = null;
            }
        }
        ui.setClientWsServer(clientWsServer);
        if (processes.clientWsHolder() != null) {
            processes.clientWsHolder().set(clientWsServer);
        }
        if (clientWsServer != null) {
            clientWsServer.setKopcheniPythonPool(processes.pythonPool());
            clientWsServer.attachPipelineReferences(pipeline.pipelineReferenceRegistry(), pipeline.detectorByCamera());
            clientWsServer.setCaptureStage(processes.captureCoordinator());
            clientWsServer.setLightTriggerClient(pipeline.lightClient());
            clientWsServer.setLightBrightnessStore(pipeline.lightBrightnessStore());
            clientWsServer.setReferenceCameraIds(ConfiguredCameras.enabledIds(env.root()));
        }
        processes.uiSidecar().setClientWebSocketServer(clientWsServer);
        if (processes.clientApiMount().enabled()) {
            log.info(
                    "client_api enabled (same port as ui_http): kopcheni_proxy={} kopcheni_base_url={}",
                    processes.clientApiMount().kopcheniConfigured(),
                    processes.clientApiMount().kopcheniBaseUrl()
            );
        }
        ui.setUiVisualsPython(processes.uiSidecar().resolveVisualsDetector(
                processes.uiCfg(),
                processes.pythonPool().isEmpty() ? null : processes.pythonPool().get(0)
        ));
        ui.setUiArtifactsExecutor(processes.uiSidecar().startUiPublishExecutorIfEnabled(processes.uiCfg()));
        processes.uiSidecar().bindPublishContext(
                ui.uiServer(),
                processes.uiCfg(),
                ui.uiVisualsPython(),
                ui.uiArtifactsExecutor()
        );
    }
}
