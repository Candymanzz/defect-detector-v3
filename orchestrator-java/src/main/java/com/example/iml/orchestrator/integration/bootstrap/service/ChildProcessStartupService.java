package com.example.iml.orchestrator.integration.bootstrap.service;

import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.factory.IntegrationServicePoolFactory;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeStore;
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
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.subprocess.IntegrationExternalProcessLauncher;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;
import com.example.iml.orchestrator.integration.ui.UiArtifactsSidecar;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Запуск дочерних процессов и пулов RPC/HTTP; сборка ранних collaborator'ов capture/API.
 * Тяжёлые внешние сервисы стартуют параллельно.
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
        List<String> geometryCommand = ctx.bootConfig().geometryCommand();
        List<String> positioningCommand = CameraWorkerPaths.pickIntegrationCommandList(
                ctx.integration(), ctx.windows(), "positioning_command_windows", "positioning_command_linux");
        LightServersConfig lightServersCfg = LightServersConfig.fromRootYaml(ctx.root());
        InspectionTriggerConfig inspectionTriggerConfig = InspectionTriggerConfig.parse(ctx.integration());
        boolean startFrontend = shouldAutostartFrontend();

        ExecutorService boot = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "svc-boot");
            t.setDaemon(true);
            return t;
        });
        long t0 = System.nanoTime();
        try {
            CompletableFuture<AnalisSurfaceLauncher.PoolStartResult> analisFuture = CompletableFuture.supplyAsync(
                    () -> analisSurfaceLauncher.startPoolIfConfigured(
                            ctx.integration(),
                            ctx.projectRoot(),
                            ctx.windows(),
                            pythonDetectorCfg.baseUrl(),
                            ctx.bootConfig().pythonServerPoolSize(),
                            ctx.bootConfig().workerStartupStaggerMs()
                    ),
                    boot
            );
            CompletableFuture<List<ServiceProcessSupervisor>> geometryFuture = CompletableFuture.supplyAsync(
                    () -> poolFactory.createGeometryPool(geometryCommand, ctx.projectRoot(), ctx.bootConfig()),
                    boot
            );
            CompletableFuture<List<ServiceProcessSupervisor>> positioningFuture = CompletableFuture.supplyAsync(
                    () -> poolFactory.createPositioningPool(
                            ctx.root(),
                            ctx.integration(),
                            positioningCommand,
                            ctx.projectRoot(),
                            ctx.bootConfig()
                    ),
                    boot
            );
            CompletableFuture<ExternalServiceProcess> lightFuture = CompletableFuture.supplyAsync(() -> {
                if (!lightServersCfg.enabled()) {
                    log.info("light_servers.enabled=false — LightServer не запускается, COM-вспышки отключены");
                    return null;
                }
                return lightServerLauncher.startIfConfigured(
                        ctx.integration(), ctx.projectRoot(), ctx.windows(), ctx.bootConfig().lightStartupDelayMs());
            }, boot);
            CompletableFuture<ExternalServiceProcess> ioFuture = CompletableFuture.supplyAsync(() -> {
                if (!inspectionTriggerConfig.usesIoInputMonitor()) {
                    return null;
                }
                return externalProcessLauncher.startIfConfigured(
                        ctx.integration(),
                        ctx.projectRoot(),
                        ctx.windows(),
                        "io_input_monitor_autostart",
                        "io_input_monitor_command_windows",
                        "io_input_monitor_command_linux",
                        "io-input-monitor",
                        "."
                );
            }, boot);
            CompletableFuture<ExternalServiceProcess> frontendFuture = CompletableFuture.supplyAsync(() -> {
                if (!startFrontend) {
                    return null;
                }
                return externalProcessLauncher.startIfConfigured(
                        ctx.integration(),
                        ctx.projectRoot(),
                        ctx.windows(),
                        "frontend_autostart",
                        "frontend_command_windows",
                        "frontend_command_linux",
                        "frontend",
                        "front-end"
                );
            }, boot);

            CompletableFuture.allOf(
                    analisFuture, geometryFuture, positioningFuture, lightFuture, ioFuture, frontendFuture
            ).join();

            AnalisSurfaceLauncher.PoolStartResult analisSurfacePool = analisFuture.join();
            ctx.setAnalisSurfaceProcesses(analisSurfacePool.processes());
            ctx.setGeometryPool(geometryFuture.join());
            ctx.setPositioningPool(positioningFuture.join());
            ctx.setLightServerProcess(lightFuture.join());
            ctx.setIoInputMonitorProcess(ioFuture.join());
            ctx.setFrontendProcess(frontendFuture.join());

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
            log.info(
                    "child services boot parallel done in {} ms (geometry={} positioning={})",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0),
                    ctx.geometryPool().size(),
                    ctx.positioningPool().size()
            );
        } finally {
            boot.shutdownNow();
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
        ctx.setGeometryRuntimeConfig(new GeometryRuntimeConfig(openGeometryRuntimeStore(ctx.projectRoot())));
        ctx.setInspectionGate(PerCameraInspectionGate.fromCameras(ctx.cameras()));
        ctx.setManualLineDirection(new ManualLineDirectionService());
        ctx.setPlcFinsHolder(new PlcFinsServiceHolder());
        var clientWsHolder = new com.example.iml.orchestrator.integration.clientws.ClientWsServiceHolder();
        ctx.setClientWsHolder(clientWsHolder);
        var inspectionResumeHolder = new com.example.iml.orchestrator.integration.pipeline.session.InspectionCycleResumeHolder();
        ctx.setClientApiMount(ClientApiMount.fromRootYaml(
                ctx.root(),
                ctx.geometryRuntimeConfig(),
                ctx.inspectionGate(),
                ctx.manualLineDirection(),
                ctx.plcFinsHolder(),
                clientWsHolder,
                inspectionResumeHolder
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

    private GeometryRuntimeStore openGeometryRuntimeStore(Path projectRoot) {
        Path storagePath = projectRoot.resolve("config/data/geometry_runtime_settings.json");
        try {
            return GeometryRuntimeStore.open(storagePath);
        } catch (IOException e) {
            log.warn("geometry runtime store unavailable path={}: {}", storagePath.toAbsolutePath(), e.getMessage());
            return null;
        }
    }

    /** {@code IML_FRONTEND_AUTOSTART=false} — отключить UI при {@code run.ps1 -NoFrontend}. */
    private static boolean shouldAutostartFrontend() {
        String raw = System.getenv("IML_FRONTEND_AUTOSTART");
        return raw == null || !raw.equalsIgnoreCase("false");
    }
}
