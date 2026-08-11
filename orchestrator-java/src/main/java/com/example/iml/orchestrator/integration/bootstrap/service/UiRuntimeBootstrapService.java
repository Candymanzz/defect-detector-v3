package com.example.iml.orchestrator.integration.bootstrap.service;

import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.ui.FrameArchiveConfig;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * UI HTTP, client WebSocket, frame archive.
 */
public final class UiRuntimeBootstrapService {

    private final Logger log;

    public UiRuntimeBootstrapService(Logger log) {
        this.log = log;
    }

    public void bootstrap(IntegrationRuntimeContext ctx) {
        ctx.setCameraSettingsStore(openCameraSettingsStore(ctx.projectRoot()));
        FrameArchiveService frameArchiveService = null;
        try {
            frameArchiveService = FrameArchiveService.open(FrameArchiveConfig.fromRootYaml(ctx.root(), ctx.windows()));
        } catch (Exception e) {
            log.warn("frame archive failed to start: {}", e.getMessage());
        }
        ctx.setFrameArchiveService(frameArchiveService);

        ctx.setUiServer(ctx.uiSidecar().startHttpServerIfEnabled(
                ctx.uiCfg(),
                ctx.geometrySnapshotCache(),
                ctx.clientApiMount(),
                ctx.lightClient(),
                ctx.root(),
                ctx.cameraSettingsStore(),
                ctx.lightBrightnessStore(),
                frameArchiveService
        ));
        ctx.uiSidecar().setFrameArchiveService(frameArchiveService);

        ClientWsConfig clientWsCfg = ClientWsConfig.fromRootYaml(ctx.root());
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
        ctx.setClientWsServer(clientWsServer);
        if (ctx.clientWsHolder() != null) {
            ctx.clientWsHolder().set(clientWsServer);
        }
        if (clientWsServer != null) {
            clientWsServer.setKopcheniPythonPool(ctx.pythonPool());
            clientWsServer.attachPipelineReferences(ctx.pipelineReferenceRegistry(), ctx.detectorByCamera());
            clientWsServer.setCaptureStage(ctx.captureCoordinator());
            clientWsServer.setLightTriggerClient(ctx.lightClient());
            clientWsServer.setLightBrightnessStore(ctx.lightBrightnessStore());
            clientWsServer.setReferenceCameraIds(ConfiguredCameras.enabledIds(ctx.root()));
        }
        ctx.uiSidecar().setClientWebSocketServer(clientWsServer);
        if (ctx.clientApiMount().enabled()) {
            log.info(
                    "client_api enabled (same port as ui_http): kopcheni_proxy={} kopcheni_base_url={}",
                    ctx.clientApiMount().kopcheniConfigured(),
                    ctx.clientApiMount().kopcheniBaseUrl()
            );
        }
        ctx.setUiVisualsPython(ctx.uiSidecar().resolveVisualsDetector(
                ctx.uiCfg(),
                ctx.pythonPool().isEmpty() ? null : ctx.pythonPool().get(0)
        ));
        ctx.setUiArtifactsExecutor(ctx.uiSidecar().startUiPublishExecutorIfEnabled(ctx.uiCfg()));
        wireUiTestAnalyze(ctx);
    }

    private void wireUiTestAnalyze(IntegrationRuntimeContext ctx) {
        if (ctx.clientApiMount() == null || !ctx.clientApiMount().enabled()) {
            return;
        }
        var holder = ctx.clientApiMount().uiTestAnalyzeHolder();
        if (holder == null) {
            return;
        }
        if (ctx.inspectionPipeline() == null || ctx.pipelineReferenceRegistry() == null) {
            log.warn("ui test-analyze not wired: pipeline/reference missing");
            return;
        }
        var svc = ctx.inspectionPipeline().services();
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ui-test-analyze");
            t.setDaemon(true);
            return t;
        });
        holder.set(new com.example.iml.orchestrator.integration.clientapi.UiTestAnalyzeService(
                log,
                ctx.pipelineReferenceRegistry(),
                ctx.detectorByCamera(),
                ctx.geometryCfg(),
                ctx.pythonCfg(),
                ctx.geometryPool(),
                ctx.pythonPool(),
                svc.geometryStage(),
                svc.pythonStage(),
                svc.decisionPolicy(),
                svc.afterInspectionSidecar(),
                ctx.frameArchiveService(),
                ctx::uiServer,
                ctx::uiCfg,
                ctx::uiVisualsPython,
                ctx::uiArtifactsExecutor,
                worker
        ));
        log.info("ui test-analyze endpoint ready: POST /api/client/inspection/test-analyze");
    }

    private CameraSettingsStore openCameraSettingsStore(Path projectRoot) {
        Path storagePath = projectRoot.resolve("config/data/camera_runtime_settings.json");
        try {
            return CameraSettingsStore.open(storagePath);
        } catch (IOException e) {
            log.warn("camera settings store unavailable path={}: {}", storagePath.toAbsolutePath(), e.getMessage());
            return null;
        }
    }
}
