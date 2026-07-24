package com.example.iml.orchestrator.integration.bootstrap.service;

import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.factory.IntegrationServicePoolFactory;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.integration.config.CameraWorkerPaths;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.PythonDetectorConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.lighting.LightServerLauncher;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.pipeline.stages.CaptureFrameDownscaleService;
import com.example.iml.orchestrator.integration.pipeline.stages.WorkerCaptureCoordinator;
import com.example.iml.orchestrator.integration.plc.PlcFinsServiceHolder;
import com.example.iml.orchestrator.integration.python.AnalisSurfaceLauncher;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.subprocess.IntegrationExternalProcessLauncher;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;
import com.example.iml.orchestrator.integration.ui.UiArtifactsSidecar;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * Запуск дочерних процессов и пулов RPC/HTTP; сборка ранних collaborator'ов capture/API.
 */
public final class ChildProcessStartupService {

    private final Logger log;
    private final AnalisSurfaceLauncher analisSurfaceLauncher;
    private final LightServerLauncher lightServerLauncher;
    private final IntegrationExternalProcessLauncher externalProcessLauncher;

    public ChildProcessStartupService(Logger log) {
        this.log = log;
        this.analisSurfaceLauncher = new AnalisSurfaceLauncher(log);
        this.lightServerLauncher = new LightServerLauncher(log);
        this.externalProcessLauncher = new IntegrationExternalProcessLauncher(log);
    }

    /**
     * @return {@code false} при пустом python pool (early-exit)
     */
    @SuppressWarnings("unchecked")
    public boolean start(IntegrationRuntimeContext ctx, IntegrationServicePoolFactory poolFactory) {
        assembleCoreCollaborators(ctx);

        PythonDetectorConfig pythonDetectorCfg = PythonDetectorConfig.fromRootYaml(ctx.root());
        AnalisSurfaceLauncher.PoolStartResult analisSurfacePool = analisSurfaceLauncher.startPoolIfConfigured(
                ctx.integration(),
                ctx.projectRoot(),
                ctx.windows(),
                pythonDetectorCfg.baseUrl(),
                ctx.bootConfig().pythonServerPoolSize(),
                ctx.bootConfig().workerStartupStaggerMs()
        );
        ctx.setAnalisSurfaceProcesses(analisSurfacePool.processes());

        ctx.setPythonPool(poolFactory.createPythonHttpPool(
                analisSurfacePool.baseUrls(),
                ctx.bootConfig()
        ));
        if (ctx.pythonPool().isEmpty()) {
            log.error(
                    "analisSurface FastAPI pool is empty (urls={}). "
                            + "Проверьте venv в analisSurface/backend, integration.analis_surface_autostart и python_detector.base_url.",
                    analisSurfacePool.baseUrls()
            );
            for (ExternalServiceProcess process : ctx.analisSurfaceProcesses()) {
                process.close();
            }
            return false;
        }
        log.info(
                "python detector transport=http servers={} clients={} autostart={} urls={}",
                analisSurfacePool.baseUrls().size(),
                ctx.pythonPool().size(),
                analisSurfacePool.autostartEnabled(),
                analisSurfacePool.baseUrls()
        );

        List<String> geometryCommand = ctx.bootConfig().geometryCommand();
        ctx.setGeometryPool(poolFactory.createGeometryPool(
                geometryCommand,
                ctx.projectRoot(),
                ctx.bootConfig()
        ));

        List<String> positioningCommand = CameraWorkerPaths.pickIntegrationCommandList(
                ctx.integration(), ctx.windows(), "positioning_command_windows", "positioning_command_linux");
        ctx.setPositioningPool(poolFactory.createPositioningPool(
                ctx.root(),
                ctx.integration(),
                positioningCommand,
                ctx.projectRoot(),
                ctx.bootConfig()
        ));

        LightServersConfig lightServersCfg = LightServersConfig.fromRootYaml(ctx.root());
        if (lightServersCfg.enabled()) {
            ctx.setLightServerProcess(lightServerLauncher.startIfConfigured(
                    ctx.integration(), ctx.projectRoot(), ctx.windows(), ctx.bootConfig().lightStartupDelayMs()));
        } else {
            log.info("light_servers.enabled=false — LightServer не запускается, COM-вспышки отключены");
        }

        InspectionTriggerConfig inspectionTriggerConfig = InspectionTriggerConfig.parse(ctx.integration());
        if (inspectionTriggerConfig.usesIoInputMonitor()) {
            ctx.setIoInputMonitorProcess(externalProcessLauncher.startIfConfigured(
                    ctx.integration(),
                    ctx.projectRoot(),
                    ctx.windows(),
                    "io_input_monitor_autostart",
                    "io_input_monitor_command_windows",
                    "io_input_monitor_command_linux",
                    "io-input-monitor",
                    "."
            ));
        }
        if (shouldAutostartFrontend()) {
            ctx.setFrontendProcess(externalProcessLauncher.startIfConfigured(
                    ctx.integration(),
                    ctx.projectRoot(),
                    ctx.windows(),
                    "frontend_autostart",
                    "frontend_command_windows",
                    "frontend_command_linux",
                    "frontend",
                    "front-end"
            ));
        }

        ctx.setPythonCfg((Map<String, Object>) ctx.root().get("python_detector"));
        ctx.setGeometryCfg((Map<String, Object>) ctx.root().get("java_geometry"));
        Map<String, Object> positioningCfgEarly = (Map<String, Object>) ctx.root().get("java_positioning");
        boolean positioningEnabled = YamlScalars.toBool(
                positioningCfgEarly == null ? null : positioningCfgEarly.get("enabled"),
                true
        );
        Map<String, Object> positioningCfg = positioningCfgEarly != null
                ? positioningCfgEarly
                : Map.of("enabled", positioningEnabled);
        ctx.setPositioningCfg(positioningCfg);
        ctx.setUiCfg((Map<String, Object>) ctx.root().get("ui_http"));
        return true;
    }

    private void assembleCoreCollaborators(IntegrationRuntimeContext ctx) {
        ctx.setUiSidecar(new UiArtifactsSidecar(log));
        ctx.setGeometrySnapshotCache(new GeometrySnapshotCache());
        ctx.setGeometryRuntimeConfig(new GeometryRuntimeConfig());
        ctx.setInspectionGate(PerCameraInspectionGate.fromCameras(ctx.cameras()));
        ctx.setManualLineDirection(new ManualLineDirectionService());
        ctx.setPlcFinsHolder(new PlcFinsServiceHolder());
        var clientWsHolder = new com.example.iml.orchestrator.integration.clientws.ClientWsServiceHolder();
        ctx.setClientWsHolder(clientWsHolder);
        ctx.setClientApiMount(ClientApiMount.fromRootYaml(
                ctx.root(),
                ctx.geometryRuntimeConfig(),
                ctx.inspectionGate(),
                ctx.manualLineDirection(),
                ctx.plcFinsHolder(),
                clientWsHolder
        ));

        FrameJpegWriter jpegWriter = new FrameJpegWriter(log);
        IntegrationFeatureConfig.CaptureFrameDownscaleConfig captureDownscaleCfg =
                IntegrationFeatureConfig.parseCaptureFrameDownscale(ctx.integration());
        CaptureFrameDownscaleService captureDownscaleService = captureDownscaleCfg.enabled()
                ? new CaptureFrameDownscaleService(log, captureDownscaleCfg.scale())
                : null;
        if (captureDownscaleCfg.enabled()) {
            log.info(
                    "capture_frame_downscale enabled scale={} apply_inspection={} apply_reference={} apply_client_reference={}",
                    captureDownscaleCfg.scale(),
                    captureDownscaleCfg.applyToInspectionCapture(),
                    captureDownscaleCfg.applyToReferenceCapture(),
                    captureDownscaleCfg.applyToClientReferenceBundle()
            );
        }
        WorkerCaptureCoordinator captureCoordinator = new WorkerCaptureCoordinator(
                log,
                jpegWriter,
                captureDownscaleService,
                captureDownscaleCfg.applyToInspectionCapture(),
                captureDownscaleCfg.applyToReferenceCapture(),
                captureDownscaleCfg.applyToClientReferenceBundle(),
                IntegrationFeatureConfig.parseCaptureWithoutReference(ctx.integration())
        );
        ctx.setCaptureCoordinator(captureCoordinator);
    }

    /** {@code IML_FRONTEND_AUTOSTART=false} — отключить UI при {@code run.ps1 -NoFrontend}. */
    private static boolean shouldAutostartFrontend() {
        String raw = System.getenv("IML_FRONTEND_AUTOSTART");
        return raw == null || !raw.equalsIgnoreCase("false");
    }
}
