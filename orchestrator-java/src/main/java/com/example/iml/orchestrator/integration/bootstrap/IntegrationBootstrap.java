package com.example.iml.orchestrator.integration.bootstrap;

import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationShutdownCoordinator;
import com.example.iml.orchestrator.integration.camera.WorkerIpcMode;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.camera.CameraSettingsService;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.camera.CameraWorkersHolder;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.capture.ImlShmJanitor;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.config.CameraWorkerPaths;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.PythonDetectorConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.lighting.LightServerLauncher;
import com.example.iml.orchestrator.integration.python.AnalisSurfaceLauncher;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessUpdate;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipeline;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.preview.LivePreviewPublisher;
import com.example.iml.orchestrator.integration.preview.LivePreviewGate;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.stream.ClientStreamConfig;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.pipeline.reference.ReferenceSnapshotBootstrap;
import com.example.iml.orchestrator.integration.pipeline.decision.DefaultInspectionDecisionAggregator;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectGeometryExecutor;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPositioningExecutor;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPythonExecutor;
import com.example.iml.orchestrator.integration.pipeline.stages.WorkerCaptureCoordinator;
import com.example.iml.orchestrator.integration.pipeline.stages.CaptureFrameDownscaleService;
import com.example.iml.orchestrator.integration.pipeline.telemetry.PipelineInspectionTelemetry;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionConfig;
import com.example.iml.orchestrator.integration.trigger.BucketLineTriggerBroadcaster;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerRuntime;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategy;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategyFactory;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.trigger.strategy.BusTriggerStrategy;
import com.example.iml.orchestrator.integration.services.ServicePoolLifecycle;
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.subprocess.IntegrationExternalProcessLauncher;
import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;
import com.example.iml.orchestrator.integration.ui.UiArtifactsSidecar;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Сборка и жизненный цикл интеграции: воркеры камер, вспомогательные сервисы, запуск {@link InspectionPipeline}.
 */
public final class IntegrationBootstrap {

    private static final Logger log = LogManager.getLogger(IntegrationBootstrap.class);

    public void start(Map<String, Object> root, Path projectRoot) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cameras = enabledCameras((List<Map<String, Object>>) root.get("cameras"));
        if (cameras.isEmpty()) {
            log.warn("No enabled cameras in config; integration pipeline skipped");
            return;
        }
        log.info("configured cameras: {}", ConfiguredCameras.enabledIds(root));
        ImlShmJanitor.purgeStaleFiles(log);

        Path workerBin = CameraWorkerPaths.resolveCameraWorkerExecutable(projectRoot);
        @SuppressWarnings("unchecked")
        Map<String, Object> integration = (Map<String, Object>) root.get("integration");
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!Files.isRegularFile(workerBin)) {
            log.error(
                    "camera-worker binary not found at {}. Build the worker or place camera_worker (exe) under camera-worker/build/; integration pipeline not started.",
                    workerBin.toAbsolutePath()
            );
            return;
        }

        List<String> geometryCommand = CameraWorkerPaths.pickIntegrationCommandList(integration, isWindows, "geometry_command_windows", "geometry_command_linux");
        List<String> positioningCommand = CameraWorkerPaths.pickIntegrationCommandList(integration, isWindows, "positioning_command_windows", "positioning_command_linux");
        IntegrationBootConfig cfg = IntegrationBootConfig.load(integration, cameras.size(), isWindows).withPoolCommands(List.of(), geometryCommand);
        PythonDetectorConfig pythonDetectorCfg = PythonDetectorConfig.fromRootYaml(root);

        ServicePoolLifecycle servicePools = new ServicePoolLifecycle(log);
        AnalisSurfaceLauncher analisSurfaceLauncher = new AnalisSurfaceLauncher(log);
        LightServerLauncher lightServerLauncher = new LightServerLauncher(log);
        IntegrationExternalProcessLauncher externalProcessLauncher = new IntegrationExternalProcessLauncher(log);
        UiArtifactsSidecar uiSidecar = new UiArtifactsSidecar(log);
        GeometrySnapshotCache geometrySnapshotCache = new GeometrySnapshotCache();
        GeometryRuntimeConfig geometryRuntimeConfig = new GeometryRuntimeConfig();
        PerCameraInspectionGate inspectionGate = PerCameraInspectionGate.fromCameras(cameras);
        ManualLineDirectionService manualLineDirection = new ManualLineDirectionService();
        ClientApiMount clientApiMount = ClientApiMount.fromRootYaml(
                root,
                geometryRuntimeConfig,
                inspectionGate,
                manualLineDirection
        );
        FrameJpegWriter jpegWriter = new FrameJpegWriter(log);
        IntegrationFeatureConfig.CaptureFrameDownscaleConfig captureDownscaleCfg =
                IntegrationFeatureConfig.parseCaptureFrameDownscale(integration);
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
                IntegrationFeatureConfig.parseCaptureWithoutReference(integration)
        );
        PipelineInspectionTelemetry pipelineTelemetry = new PipelineInspectionTelemetry();
        ReferenceSnapshotBootstrap referenceBootstrap = new ReferenceSnapshotBootstrap(log, captureCoordinator, pipelineTelemetry);

        AnalisSurfaceLauncher.PoolStartResult analisSurfacePool = analisSurfaceLauncher.startPoolIfConfigured(
                integration,
                projectRoot,
                isWindows,
                pythonDetectorCfg.baseUrl(),
                cfg.pythonServerPoolSize(),
                cfg.workerStartupStaggerMs()
        );
        List<ExternalServiceProcess> analisSurfaceProcesses = analisSurfacePool.processes();

        List<BinaryRpcSupervisor> pythonPool = servicePools.startAnalisSurfaceHttpPool(
                analisSurfacePool.baseUrls(),
                cfg.pythonParallelism(),
                cfg.serviceCommandTimeoutMs()
        );
        if (pythonPool.isEmpty()) {
            log.error(
                    "analisSurface FastAPI pool is empty (urls={}). "
                            + "Проверьте venv в analisSurface/backend, integration.analis_surface_autostart и python_detector.base_url.",
                    analisSurfacePool.baseUrls()
            );
            for (ExternalServiceProcess process : analisSurfaceProcesses) {
                process.close();
            }
            return;
        }
        log.info(
                "python detector transport=http servers={} clients={} autostart={} urls={}",
                analisSurfacePool.baseUrls().size(),
                pythonPool.size(),
                analisSurfacePool.autostartEnabled(),
                analisSurfacePool.baseUrls()
        );
        List<ServiceProcessSupervisor> geometryPool = servicePools.startOptionalPool(
                geometryCommand,
                projectRoot,
                "java-geometry",
                cfg.serviceCommandTimeoutMs(),
                cfg.geometryPoolSize()
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> positioningCfgEarly = (Map<String, Object>) root.get("java_positioning");
        boolean positioningEnabled = YamlScalars.toBool(
                positioningCfgEarly == null ? null : positioningCfgEarly.get("enabled"),
                true
        );
        int positioningPoolSize = Math.max(
                1,
                YamlScalars.toInt(integration == null ? null : integration.get("positioning_pool_size"), cfg.geometryPoolSize())
        );
        List<ServiceProcessSupervisor> positioningPool = positioningEnabled
                ? servicePools.startOptionalPool(
                        positioningCommand,
                        projectRoot,
                        "java-positioning",
                        cfg.serviceCommandTimeoutMs(),
                        positioningPoolSize
                )
                : List.of();
        if (positioningEnabled && positioningPool.isEmpty()) {
            log.warn("java-positioning enabled but pool is empty — positioning stage will be skipped");
        } else if (!positioningPool.isEmpty()) {
            log.info("positioning pool size={} command={}", positioningPool.size(), positioningCommand);
        }
        ExternalServiceProcess lightServerProcess = lightServerLauncher.startIfConfigured(
                integration, projectRoot, isWindows, cfg.lightStartupDelayMs());
        InspectionTriggerConfig inspectionTriggerConfigEarly = InspectionTriggerConfig.parse(integration);
        ExternalServiceProcess ioInputMonitorProcess = inspectionTriggerConfigEarly.usesIoInputMonitor()
                ? externalProcessLauncher.startIfConfigured(
                        integration,
                        projectRoot,
                        isWindows,
                        "io_input_monitor_autostart",
                        "io_input_monitor_command_windows",
                        "io_input_monitor_command_linux",
                        "io-input-monitor",
                        "."
                )
                : null;
        ExternalServiceProcess frontendProcess = shouldAutostartFrontend()
                ? externalProcessLauncher.startIfConfigured(
                        integration,
                        projectRoot,
                        isWindows,
                        "frontend_autostart",
                        "frontend_command_windows",
                        "frontend_command_linux",
                        "frontend",
                        "front-end"
                )
                : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> pythonCfg = (Map<String, Object>) root.get("python_detector");
        @SuppressWarnings("unchecked")
        Map<String, Object> geometryCfg = (Map<String, Object>) root.get("java_geometry");
        @SuppressWarnings("unchecked")
        Map<String, Object> positioningCfg = positioningCfgEarly != null
                ? positioningCfgEarly
                : (Map<String, Object>) root.get("java_positioning");
        if (positioningCfg == null) {
            positioningCfg = Map.of("enabled", positioningEnabled);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> uiCfg = (Map<String, Object>) root.get("ui_http");
        Semaphore positioningSlots = new Semaphore(Math.max(1, positioningPool.size()));
        AtomicInteger positioningRoundRobin = new AtomicInteger();
        InspectPositioningExecutor positioningExecutor = new InspectPositioningExecutor(
                log,
                positioningPool,
                positioningSlots,
                positioningRoundRobin,
                positioningCfg
        );
        InspectionPipelineServices pipelineServices = new InspectionPipelineServices(
                log,
                new DefaultInspectionDecisionAggregator(log),
                pipelineTelemetry,
                new InspectGeometryExecutor(log, geometrySnapshotCache, geometryRuntimeConfig, positioningExecutor),
                new InspectPythonExecutor(log, geometryRuntimeConfig),
                captureCoordinator,
                referenceBootstrap,
                uiSidecar
        );
        final InspectionPipeline inspectionPipeline = new InspectionPipeline(pipelineServices);
        int flashLeadMs = LightServersConfig.flashLeadMsFromRoot(root);
        if (flashLeadMs > 0) {
            log.info("light_servers flash_lead_ms={} (пауза после старта POST вспышки, перед capture)", flashLeadMs);
        }
        LightBrightnessStore lightBrightnessStore = openLightBrightnessStore(projectRoot);
        LightTriggerClient lightClient = LightTriggerClient.fromRootYaml(root);
        applyPersistedLightBrightness(lightClient, lightBrightnessStore);
        if (lightClient.isEnabled()) {
            log.info("waiting for LightServer COM bank (GET /api/com/light)...");
            lightClient.awaitEndpointsReady();
            lightClient.startupEngage();
            if (lightClient.isHoldMode()) {
                log.info("light_servers hold_mode=true — постоянная подсветка, без On/Off на каждый кадр");
            }
        }
        PipelineReferenceRegistry pipelineReferenceRegistry = new PipelineReferenceRegistry();
        Map<Integer, String> detectorByCamera = new LinkedHashMap<>();
        for (Map<String, Object> camera : cameras) {
            int cameraId = ((Number) camera.get("id")).intValue();
            detectorByCamera.put(cameraId, String.valueOf(camera.getOrDefault("detector", "v1")));
        }
        if (cfg.referenceSource() == com.example.iml.orchestrator.integration.config.ReferenceSource.CLIENT) {
            log.info("integration.reference_source=client — эталон только через client.reference_bundle (WebSocket)");
        }

        ClientWebSocketServer clientWsServer = null;
        CameraSettingsStore cameraSettingsStore = openCameraSettingsStore(projectRoot);
        final UiHttpServer uiServer = uiSidecar.startHttpServerIfEnabled(
                uiCfg, geometrySnapshotCache, clientApiMount, lightClient, root, cameraSettingsStore, lightBrightnessStore);
        ClientWsConfig clientWsCfg = ClientWsConfig.fromRootYaml(root);
        if (clientWsCfg.enabled()) {
            try {
                clientWsServer = new ClientWebSocketServer(log, clientWsCfg);
                clientWsServer.begin();
            } catch (Exception e) {
                log.warn("client_ws failed to start: {}", e.getMessage());
                clientWsServer = null;
            }
        }
        if (clientWsServer != null) {
            clientWsServer.setKopcheniPythonPool(pythonPool);
            clientWsServer.attachPipelineReferences(pipelineReferenceRegistry, detectorByCamera);
            clientWsServer.setCaptureStage(captureCoordinator);
            clientWsServer.setLightTriggerClient(lightClient);
            clientWsServer.setReferenceCameraIds(ConfiguredCameras.enabledIds(root));
        }
        uiSidecar.setClientWebSocketServer(clientWsServer);
        if (clientApiMount.enabled()) {
            log.info(
                    "client_api enabled (same port as ui_http): kopcheni_proxy={} kopcheni_base_url={}",
                    clientApiMount.kopcheniConfigured(),
                    clientApiMount.kopcheniBaseUrl()
            );
        }
        final BinaryRpcSupervisor uiVisualsPython = uiSidecar.resolveVisualsDetector(
                uiCfg,
                pythonPool.isEmpty() ? null : pythonPool.get(0)
        );
        final ExecutorService uiArtifactsExecutor = uiSidecar.startUiPublishExecutorIfEnabled(uiCfg);
        FanOutCoordinator fanOut = null;
        ExecutorService cameraExecutor = null;
        ExecutorService captureStageExecutor = null;
        ExecutorService pythonStageExecutor = null;
        ExecutorService geometryStageExecutor = null;
        ExecutorService decisionStageExecutor = null;
        Map<Integer, WorkerProcessSupervisor> workersByCamera = new LinkedHashMap<>();
        Map<Integer, ReferenceSnapshot> referenceByCamera = pipelineReferenceRegistry.byCamera();
        PipelineStagesLog pipelineStagesLogMutable = null;
        Path workerConfigPath = CameraWorkerPaths.resolveWorkerConfigPath(projectRoot, integration);
        if (!Files.isRegularFile(workerConfigPath)) {
            log.error("Файл конфигурации camera-worker не найден: {} (integration.worker_config_json)", workerConfigPath.toAbsolutePath());
        } else {
            log.info("camera_worker config={}", workerConfigPath.toAbsolutePath());
        }

        LivePreviewPublisher livePreview = null;
        LivePreviewGate livePreviewGate = new LivePreviewGate();
        LineSynchronizedCaptureCoordinator lineCaptureCoordinator = null;
        CameraStreamService cameraStreamService = null;
        InspectionTriggerRuntime triggerRuntime = null;
        BucketLineTriggerBroadcaster bucketLineTriggerBroadcaster = null;
        BucketInspectionAggregator bucketInspectionAggregator = null;
        try {
            IntegrationFeatureConfig.TimingStagesLogConfig timingStagesLogCfg = IntegrationFeatureConfig.parseTimingStagesLog(integration);
            if (timingStagesLogCfg.enabled()) {
                try {
                    Path timingPath = projectRoot.resolve(timingStagesLogCfg.relativePath());
                    pipelineStagesLogMutable = new PipelineStagesLog(timingPath);
                    log.info("timing_stages_log enabled jsonl={} (рядом .txt с тем же базовым именем)", timingPath);
                } catch (Exception e) {
                    log.warn("timing_stages_log init failed: {}", e.getMessage());
                }
            }
            final PipelineStagesLog pipelineStagesLog = pipelineStagesLogMutable;
            FanOutCoordinator activeFanOut = FanOutCoordinator.fromConfig(root, projectRoot, clientWsServer);
            fanOut = activeFanOut;
            log.info("integration parallel settings: camera_parallelism={} geometry_pool_size={}", cfg.cameraParallelism(), geometryPool.size());
            List<Map<String, Object>> activeCameras = new ArrayList<>();
            for (Map<String, Object> camera : cameras) {
                int cameraId = ((Number) camera.get("id")).intValue();
                List<String> cmd = new ArrayList<>();
                cmd.add(workerBin.toString());
                cmd.add(workerConfigPath.toString());
                cmd.add(String.valueOf(cameraId));
                if (cfg.workerIpcMode() == WorkerIpcMode.STDIO) {
                    cmd.add("--binary-stdio");
                } else {
                    cmd.add("--named-pipe");
                    cmd.add(String.format(cfg.workerPipeTemplate(), cameraId));
                }
                String workerPipePath = String.format(cfg.workerPipeTemplate(), cameraId);
                try {
                    WorkerProcessSupervisor worker = new WorkerProcessSupervisor(
                            cameraId, cmd, projectRoot, cfg.workerIpcMode(), workerPipePath, cfg.workerPipeConnectTimeoutMs(), cfg.workerCommandTimeoutMs());
                    worker.start();
                    BinaryProtocol.Message health = worker.health();
                    log.info("worker cam={} health type={} header={}", cameraId, health.type(), health.header());
                    workersByCamera.put(cameraId, worker);
                    activeCameras.add(camera);
                    sleepWorkerStartupStagger(cfg.workerStartupStaggerMs());
                } catch (Exception e) {
                    log.error(
                            "worker cam={} failed to start/health; skipping this camera and continuing with others: {}",
                            cameraId,
                            e.getMessage()
                    );
                    log.debug("worker start failure details cam={}", cameraId, e);
                }
            }
            if (workersByCamera.isEmpty()) {
                log.error("No camera workers started successfully; integration pipeline skipped.");
                return;
            }
            applyPersistedCameraSettings(workersByCamera, cameraSettingsStore);
            Map<Integer, String> analysisProfileByCamera = new LinkedHashMap<>();
            for (Map<String, Object> camera : activeCameras) {
                int cameraId = ((Number) camera.get("id")).intValue();
                analysisProfileByCamera.put(cameraId, ConfiguredCameras.analysisProfileForCamera(camera, cameraId));
            }
            ClientStreamConfig clientStreamCfg = ClientStreamConfig.fromRootYaml(root);
            if (uiServer != null && !workersByCamera.isEmpty()) {
                uiServer.attachCameraWorkers(workersByCamera);
                cameraStreamService = new CameraStreamService(
                        log,
                        clientStreamCfg,
                        workersByCamera,
                        analysisProfileByCamera,
                        detectorByCamera,
                        uiServer,
                        clientWsServer,
                        uiCfg
                );
                captureCoordinator.setCameraStreamService(cameraStreamService);
                if (clientWsServer != null) {
                    clientWsServer.setCameraStreamService(cameraStreamService);
                    clientWsServer.setClientStreamConfig(clientStreamCfg);
                    clientWsServer.setLivePreviewGate(livePreviewGate);
                }
                uiServer.attachCameraStreamService(cameraStreamService);
                log.info("client_stream ready default_max_fps={} cap={}", clientStreamCfg.defaultMaxFps(), clientStreamCfg.maxFpsCap());
            }
            IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub =
                    IntegrationFeatureConfig.parseDevAutoTriggerStub(integration);
            livePreview = LivePreviewPublisher.start(
                    log,
                    root,
                    activeCameras,
                    workersByCamera,
                    lightClient,
                    uiServer,
                    clientWsServer,
                    flashLeadMs,
                    uiCfg,
                    cfg.referenceSource(),
                    pipelineReferenceRegistry,
                    devAutoTriggerStub,
                    cameraStreamService,
                    livePreviewGate,
                    inspectionGate
            );
            if (livePreview != null && lineCaptureCoordinator != null) {
                livePreview.setLineCaptureCoordinator(lineCaptureCoordinator);
            }
            cameraExecutor = Executors.newFixedThreadPool(cfg.cameraParallelism(), r -> {
                Thread t = new Thread(r, "camera-flow");
                t.setDaemon(true);
                return t;
            });
            captureStageExecutor = servicePools.newStageExecutor("stage-capture", cfg.cameraParallelism(), cfg.stageQueueSize());
            pythonStageExecutor = servicePools.newStageExecutor("stage-python", cfg.pythonParallelism(), cfg.stageQueueSize());
            geometryStageExecutor = servicePools.newStageExecutor("stage-geometry", Math.max(1, geometryPool.size()), cfg.stageQueueSize());
            decisionStageExecutor = servicePools.newStageExecutor("stage-decision", cfg.cameraParallelism(), cfg.stageQueueSize());
            ExecutorService activeCaptureStageExecutor = captureStageExecutor;
            ExecutorService activePythonStageExecutor = pythonStageExecutor;
            ExecutorService activeGeometryStageExecutor = geometryStageExecutor;
            ExecutorService activeDecisionStageExecutor = decisionStageExecutor;
            log.info("pipeline settings: queue_size={} python_parallelism={}", cfg.stageQueueSize(), cfg.pythonParallelism());
            Semaphore geometrySlots = new Semaphore(Math.max(1, geometryPool.size()));
            Semaphore pythonSlots = new Semaphore(Math.max(1, pythonPool.size()));
            AtomicInteger geometryRoundRobin = new AtomicInteger(0);
            AtomicInteger pythonRoundRobin = new AtomicInteger(0);
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection =
                    IntegrationFeatureConfig.parseContinuousInspection(integration);
            InspectionTriggerConfig inspectionTriggerConfig = inspectionTriggerConfigEarly;
            IntegrationFeatureConfig.InspectionTriggerMode triggerMode =
                    inspectionTriggerConfig.ioInput().di3Only()
                            || inspectionTriggerConfig.ioInput().directionLatchOnWork()
                            ? IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL
                            : IntegrationFeatureConfig.resolveInspectionTriggerMode(integration);
            if (inspectionTriggerConfig.ioInput().di3Only()) {
                log.info(
                        "inspection_trigger di3_only=true — съёмка по фронту DI{}, направление по текущему DI{}",
                        inspectionTriggerConfig.ioInput().triggerPort(),
                        inspectionTriggerConfig.ioInput().directionPort()
                );
                if (IntegrationFeatureConfig.parseDevAutoTriggerStub(integration).enabled()) {
                    log.warn("di3_only=true: dev_auto_trigger_stub включён в конфиге, но игнорируется");
                }
                if (continuousInspection.enabled()) {
                    log.warn("di3_only=true: continuous_inspection включён в конфиге, но игнорируется");
                }
            }
            if (inspectionTriggerConfig.ioInput().di3Only()
                    && inspectionTriggerConfig.ioInput().requireDirection()
                    && !inspectionTriggerConfig.ioInput().directionLatchOnWork()) {
                log.info(
                        "inspection_trigger auto direction — prefire на DI3↑ при DI2=1, dispatch на DI3↓ при DI2=0"
                );
            }
            if (inspectionTriggerConfig.ioInput().directionLatchOnWork()) {
                log.info(
                        "inspection_trigger direction_latch_on_work=true — DI2 фиксируется при DI1↑, съёмка только по DI{}",
                        inspectionTriggerConfig.ioInput().triggerPort()
                );
            }
            BucketInspectionConfig bucketInspectionConfig =
                    BucketInspectionConfig.parse(integration, workersByCamera.keySet());
            List<Integer> inspectionCameraIds = bucketInspectionConfig.enabled()
                    ? bucketInspectionConfig.allCameraIds()
                    : workersByCamera.keySet().stream().sorted().toList();
            if (bucketInspectionConfig.enabled()) {
                bucketInspectionAggregator = new BucketInspectionAggregator(log, bucketInspectionConfig);
                inspectionGate.setInspectionEnabledOnlyFor(inspectionCameraIds);
                log.info(
                        "inspection bucket enabled groups={} cameras={} timeout_ms={} line_broadcast_interval_ms={}",
                        bucketInspectionConfig.groups(),
                        bucketInspectionConfig.allCameraIds(),
                        bucketInspectionConfig.timeoutMs(),
                        bucketInspectionConfig.lineBroadcastIntervalMs()
                );
            }
            boolean lineCaptureSyncEnabled = parseSimultaneousLineCaptureEnabled(integration);
            long lineCaptureBarrierMs = parseSimultaneousLineCaptureBarrierMs(integration);
            long postTriggerSettleMs = parseSimultaneousLineCapturePostTriggerSettleMs(integration);
            long interWaitFrameMs = parseSimultaneousLineCaptureInterWaitFrameMs(integration);
            boolean parallelWaitFrame = parseSimultaneousLineCaptureParallelWaitFrame(integration);
            boolean immediatePrefire = parseSimultaneousLineCaptureImmediatePrefire(integration);
            boolean hardwareLineTrigger = parseSimultaneousLineCaptureHardwareLineTrigger(integration, root);
            int transferWaitWaves = parseSimultaneousLineCaptureTransferWaitWaves(integration, root);
            long transferWaveGapMs = parseSimultaneousLineCaptureTransferWaveGapMs(integration, root);
            boolean captureWithoutReference = IntegrationFeatureConfig.parseCaptureWithoutReference(integration);
            if (lineCaptureSyncEnabled && inspectionCameraIds.size() > 1) {
                lineCaptureCoordinator = new LineSynchronizedCaptureCoordinator(
                        inspectionCameraIds,
                        lineCaptureBarrierMs,
                        postTriggerSettleMs,
                        interWaitFrameMs,
                        parallelWaitFrame,
                        immediatePrefire,
                        hardwareLineTrigger,
                        transferWaitWaves,
                        transferWaveGapMs
                );
                lineCaptureCoordinator.bindWorkers(workersByCamera);
                captureCoordinator.setLineCaptureCoordinator(lineCaptureCoordinator);
                logGigeTopologyForLineCapture(log, root, hardwareLineTrigger);
                if (hardwareLineTrigger) {
                    log.info(
                            "hardware_line_trigger: экспозиция по DI3→Line0, Java только wait_frame (без trigger_only/settle/barrier)"
                    );
                    log.warn(
                            "hardware_line_trigger требует физическую разводку DI3→Line0 всех камер; "
                                    + "без неё wait_frame будет timeout (0x80000007)"
                    );
                }
            } else if (cfg.captureTriggerStaggerMs() > 0) {
                log.info(
                        "inspection trigger stagger enabled delay_ms={} cameras={}",
                        cfg.captureTriggerStaggerMs(),
                        inspectionCameraIds.size()
                );
            } else {
                log.info(
                        "line synchronized capture disabled (enabled={} cameras={})",
                        lineCaptureSyncEnabled,
                        inspectionCameraIds.size()
                );
            }
            final java.util.concurrent.atomic.AtomicBoolean softwareVisionReady = new java.util.concurrent.atomic.AtomicBoolean(false);
            final InspectionTriggerRuntime[] triggerRuntimeHolder = new InspectionTriggerRuntime[1];
            java.lang.Runnable refreshVisionReady = () -> {
                InspectionTriggerRuntime runtime = triggerRuntimeHolder[0];
                if (activeFanOut != null) {
                    activeFanOut.signalVisionReady(
                            softwareVisionReady.get() && (runtime == null || runtime.isLineWorkActive())
                    );
                }
            };
            triggerRuntime = InspectionTriggerRuntime.start(
                    log,
                    integration,
                    inspectionCameraIds,
                    triggerMode,
                    cfg.captureTriggerStaggerMs(),
                    refreshVisionReady,
                    triggerRuntimeHolder,
                    bucketInspectionConfig.enabled() ? bucketInspectionConfig.groups() : List.of(),
                    manualLineDirection
            );
            if (lineCaptureCoordinator != null) {
                final LineSynchronizedCaptureCoordinator lineCaptureRef = lineCaptureCoordinator;
                triggerRuntime.bus().setLineTriggerListener((seq, at, cameraIds) -> {
                    lineCaptureRef.prefireLineTrigger(seq, at.toEpochMilli(), cameraIds);
                });
            }
            InspectionTriggerStrategy sharedTriggerStrategy;
            if (bucketInspectionConfig.enabled()) {
                if (triggerMode != IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL) {
                    // Не ускорять line-broadcast ниже line_broadcast_interval_ms: иначе триггеры
                    // накапливаются быстрее, чем 5 камер успевают закрыть ведро (in-flight skip).
                    long broadcastIntervalMs = triggerMode == IntegrationFeatureConfig.InspectionTriggerMode.TIMER
                            ? devAutoTriggerStub.intervalMs()
                            : Math.max(
                                    bucketInspectionConfig.lineBroadcastIntervalMs(),
                                    continuousInspection.cycleDelayMs()
                            );
                    bucketLineTriggerBroadcaster = new BucketLineTriggerBroadcaster(
                            log,
                            triggerRuntime.bus(),
                            broadcastIntervalMs
                    );
                    bucketLineTriggerBroadcaster.start();
                }
                sharedTriggerStrategy = new BusTriggerStrategy(triggerRuntime.bus());
            } else {
                sharedTriggerStrategy = InspectionTriggerStrategyFactory.create(
                        triggerMode,
                        triggerRuntime.bus(),
                        devAutoTriggerStub,
                        continuousInspection
                );
            }
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures = IntegrationFeatureConfig.parseSaveCaptures(integration);
            if (saveCaptures.enabled()) {
                log.info("save_captures enabled dir={} (от корня проекта)", saveCaptures.relativeDir());
            }
            if (devAutoTriggerStub.enabled()) {
                log.info("dev_auto_trigger_stub enabled interval_ms={}", devAutoTriggerStub.intervalMs());
            } else if (continuousInspection.enabled()) {
                log.info("continuous_inspection enabled cycle_delay_ms={}", continuousInspection.cycleDelayMs());
            } else if (triggerMode == IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL) {
                InspectionTriggerConfig triggerCfg = inspectionTriggerConfig;
                if (triggerCfg.usesIoInputMonitor()) {
                    log.info(
                            "inspection_trigger external io_input {}:{} di={}/{}/{} trigger_edge={} di3_only={} direction_latch_on_work={} direction_arm_next_di3={} require_direction={} require_work={} direction_invert={} direction_wait_ms={} direction_poll_ms={} debounce_ms={} stub_work={}",
                            triggerCfg.udp().bindHost(),
                            triggerCfg.udp().bindPort(),
                            triggerCfg.ioInput().workPort(),
                            triggerCfg.ioInput().directionPort(),
                            triggerCfg.ioInput().triggerPort(),
                            triggerCfg.ioInput().triggerEdge(),
                            triggerCfg.ioInput().di3Only(),
                            triggerCfg.ioInput().directionLatchOnWork(),
                            triggerCfg.ioInput().directionArmNextDi3(),
                            triggerCfg.ioInput().requireDirection(),
                            triggerCfg.ioInput().requireWork(),
                            triggerCfg.ioInput().directionInvert(),
                            triggerCfg.ioInput().directionWaitMs(),
                            triggerCfg.ioInput().directionPollMs(),
                            triggerCfg.ioInput().debounceMs(),
                            triggerCfg.ioInput().stubWorkActive()
                    );
                } else if (triggerCfg.udp().enabled()) {
                    log.info(
                            "inspection_trigger external udp {}:{} format={}",
                            triggerCfg.udp().bindHost(),
                            triggerCfg.udp().bindPort(),
                            triggerCfg.udp().format()
                    );
                } else {
                    log.warn("inspection_trigger external mode but udp.enabled=false");
                }
            }
            int inspectionCycleTimeoutMs = IntegrationFeatureConfig.parseInspectionCycleTimeoutMs(integration);
            if (captureWithoutReference) {
                log.info("integration capture_without_reference enabled — trigger capture without client.reference_bundle");
            }
            log.info(
                    "inspection gate per-camera in-flight enabled timeout_ms={} cameras={}",
                    inspectionCycleTimeoutMs,
                    workersByCamera.keySet()
            );
            List<Callable<Void>> tasks = new ArrayList<>();
            final BucketInspectionAggregator activeBucketAggregator = bucketInspectionAggregator;
            final Set<Integer> activeInspectionCameraIds = Set.copyOf(inspectionCameraIds);
            for (Map<String, Object> camera : activeCameras) {
                int cameraId = ((Number) camera.get("id")).intValue();
                if (bucketInspectionConfig.enabled() && !activeInspectionCameraIds.contains(cameraId)) {
                    log.info(
                            "integration cam={}: inspection pipeline skipped (bucket cameras={})",
                            cameraId,
                            activeInspectionCameraIds
                    );
                    continue;
                }
                tasks.add(() -> {
                    WorkerProcessSupervisor worker = workersByCamera.get(cameraId);
                    if (worker == null) {
                        log.warn("camera task skipped: worker not initialized for camera {}", cameraId);
                        return null;
                    }
                    inspectionPipeline.processCamera(
                            projectRoot,
                            camera,
                            worker,
                            pythonPool,
                            geometryPool,
                            lightClient,
                            pythonCfg,
                            geometryCfg,
                            activeFanOut,
                            geometrySlots,
                            pythonSlots,
                            geometryRoundRobin,
                            pythonRoundRobin,
                            referenceByCamera,
                            cfg.referenceSource(),
                            cfg.reloadReference(),
                            activeCaptureStageExecutor,
                            activePythonStageExecutor,
                            activeGeometryStageExecutor,
                            activeDecisionStageExecutor,
                            uiCfg,
                            uiServer,
                            uiVisualsPython,
                            uiArtifactsExecutor,
                            sharedTriggerStrategy,
                            triggerMode,
                            saveCaptures,
                            flashLeadMs,
                            pipelineStagesLog,
                            inspectionGate,
                            inspectionCycleTimeoutMs,
                            activeBucketAggregator,
                            captureWithoutReference
                    );
                    return null;
                });
            }
            softwareVisionReady.set(true);
            refreshVisionReady.run();
            List<Future<Void>> futures = cameraExecutor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (Exception e) {
            log.error("Integration bootstrap failed", e);
            if (fanOut != null) {
                fanOut.signalVisionFault(true);
            }
        } finally {
            if (bucketLineTriggerBroadcaster != null) {
                bucketLineTriggerBroadcaster.close();
            }
            if (bucketInspectionAggregator != null) {
                bucketInspectionAggregator.close();
            }
            if (livePreview != null) {
                livePreview.close();
            }
            if (cameraStreamService != null) {
                cameraStreamService.close();
            }
            if (triggerRuntime != null) {
                triggerRuntime.close();
            }
            IntegrationShutdownCoordinator.shutdownAll(new IntegrationShutdownCoordinator.ShutdownResources(
                    pipelineStagesLogMutable,
                    cameraExecutor,
                    captureStageExecutor,
                    pythonStageExecutor,
                    geometryStageExecutor,
                    decisionStageExecutor,
                    workersByCamera,
                    pythonPool,
                    geometryPool,
                    lightServerProcess,
                    ioInputMonitorProcess,
                    frontendProcess,
                    analisSurfaceProcesses,
                    lightClient,
                    uiVisualsPython,
                    uiArtifactsExecutor,
                    fanOut,
                    clientWsServer,
                    uiServer,
                    servicePools,
                    log
            ));
            for (ServiceProcessSupervisor positioning : positioningPool) {
                try {
                    log.info("java-positioning supervisor restarts={}", positioning.restartCount());
                    positioning.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> enabledCameras(List<Map<String, Object>> cameras) {
        if (cameras == null || cameras.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> camera : cameras) {
            if (YamlScalars.toBool(camera.get("enabled"), true)) {
                out.add(camera);
            }
        }
        return List.copyOf(out);
    }

    private static void sleepWorkerStartupStagger(int delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean parseSimultaneousLineCaptureEnabled(Map<String, Object> integration) {
        if (integration == null) {
            return true;
        }
        Object raw = integration.get("simultaneous_line_capture");
        if (!(raw instanceof Map<?, ?> map)) {
            return true;
        }
        return YamlScalars.toBool(map.get("enabled"), true);
    }

    private static long parseSimultaneousLineCaptureBarrierMs(Map<String, Object> integration) {
        if (integration == null) {
            return 250L;
        }
        Object raw = integration.get("simultaneous_line_capture");
        if (!(raw instanceof Map<?, ?> map)) {
            return 250L;
        }
        return Math.max(0L, YamlScalars.toLong(map.get("barrier_wait_ms"), 250L));
    }

    private static boolean parseSimultaneousLineCaptureParallelWaitFrame(Map<String, Object> integration) {
        if (integration == null) {
            return true;
        }
        Object raw = integration.get("simultaneous_line_capture");
        if (!(raw instanceof Map<?, ?> map)) {
            return true;
        }
        return YamlScalars.toBool(map.get("parallel_wait_frame"), true);
    }

    private static boolean parseSimultaneousLineCaptureImmediatePrefire(Map<String, Object> integration) {
        if (integration == null) {
            return true;
        }
        Object raw = integration.get("simultaneous_line_capture");
        if (!(raw instanceof Map<?, ?> map)) {
            return true;
        }
        return YamlScalars.toBool(map.get("immediate_prefire"), true);
    }

    private static long parseSimultaneousLineCapturePostTriggerSettleMs(Map<String, Object> integration) {
        if (integration == null) {
            return 0L;
        }
        Object raw = integration.get("simultaneous_line_capture");
        if (!(raw instanceof Map<?, ?> map)) {
            return 0L;
        }
        return Math.max(0L, YamlScalars.toLong(map.get("post_trigger_settle_ms"), 0L));
    }

    private static long parseSimultaneousLineCaptureInterWaitFrameMs(Map<String, Object> integration) {
        if (integration == null) {
            return 0L;
        }
        Object raw = integration.get("simultaneous_line_capture");
        if (!(raw instanceof Map<?, ?> map)) {
            return 0L;
        }
        return Math.max(0L, YamlScalars.toLong(map.get("inter_wait_frame_ms"), 0L));
    }

    private static int parseSimultaneousLineCaptureTransferWaitWaves(
            Map<String, Object> integration,
            Map<String, Object> root
    ) {
        if (integration != null) {
            Object raw = integration.get("simultaneous_line_capture");
            if (raw instanceof Map<?, ?> map && map.containsKey("transfer_wait_waves")) {
                return Math.max(1, YamlScalars.toInt(map.get("transfer_wait_waves"), 1));
            }
        }
        int bufferKb = YamlScalars.toInt(root != null ? root.get("gige_switch_buffer_kb") : null, 0);
        int perLink = YamlScalars.toInt(root != null ? root.get("gige_ftd_cameras_per_link") : null, 0);
        if (bufferKb > 0 && bufferKb <= 96 && perLink == 2) {
            return 2;
        }
        return 1;
    }

    private static long parseSimultaneousLineCaptureTransferWaveGapMs(
            Map<String, Object> integration,
            Map<String, Object> root
    ) {
        if (integration != null) {
            Object raw = integration.get("simultaneous_line_capture");
            if (raw instanceof Map<?, ?> map && map.containsKey("transfer_wave_gap_ms")) {
                return Math.max(0L, YamlScalars.toLong(map.get("transfer_wave_gap_ms"), 0L));
            }
        }
        int bufferKb = YamlScalars.toInt(root != null ? root.get("gige_switch_buffer_kb") : null, 0);
        if (bufferKb > 256) {
            return 15L;
        }
        if (bufferKb > 96) {
            return 80L;
        }
        return 220L;
    }

    private static boolean parseSimultaneousLineCaptureHardwareLineTrigger(
            Map<String, Object> integration,
            Map<String, Object> root
    ) {
        if (integration != null) {
            Object raw = integration.get("simultaneous_line_capture");
            if (raw instanceof Map<?, ?> map && map.containsKey("hardware_line_trigger")) {
                return YamlScalars.toBool(map.get("hardware_line_trigger"), false);
            }
        }
        String mode = String.valueOf(root.getOrDefault("capture_trigger_mode", "software")).trim().toLowerCase();
        return mode.equals("line0") || mode.equals("line1") || mode.equals("line") || mode.equals("hardware");
    }

    private static void logGigeTopologyForLineCapture(
            org.apache.logging.log4j.Logger log,
            Map<String, Object> root,
            boolean hardwareLineTrigger
    ) {
        if (root == null) {
            return;
        }
        int switches = YamlScalars.toInt(root.get("gige_switch_count"), 0);
        int perSwitch = YamlScalars.toInt(root.get("gige_cameras_per_switch"), 0);
        int perLink = YamlScalars.toInt(root.get("gige_ftd_cameras_per_link"), 0);
        int bufferKb = YamlScalars.toInt(root.get("gige_switch_buffer_kb"), 0);
        String exposureMode = hardwareLineTrigger
                ? "DI3→Line0 (hardware, все камеры в один электрический момент)"
                : "software trigger_only (все камеры параллельно, ~10 мс разброс IPC)";
        log.info(
                "gige topology: switches={} cameras_per_switch={} ftd_per_link={} switch_buffer_kb={} — "
                        + "передача: {} волна(ы) wait_frame (≤96 КБ: 2 волны; >96 КБ: GevSCFTD + 1 волна); экспозиция: {}",
                switches > 0 ? switches : "?",
                perSwitch > 0 ? perSwitch : "?",
                perLink > 0 ? perLink : "?",
                bufferKb,
                bufferKb > 0 && bufferKb <= 96 && perLink == 2 ? "2" : "1",
                exposureMode
        );
        if (!hardwareLineTrigger && perLink > 0 && perLink != 2) {
            log.warn(
                    "gige_ftd_cameras_per_link={} — для 5×2 ожидается 2 (пары id 0+1, 2+3, … на коммутаторе)",
                    perLink
            );
        }
    }

    /** {@code IML_FRONTEND_AUTOSTART=false} — отключить UI при {@code run.ps1 -NoFrontend}. */
    private static boolean shouldAutostartFrontend() {
        String raw = System.getenv("IML_FRONTEND_AUTOSTART");
        return raw == null || !raw.equalsIgnoreCase("false");
    }

    private static CameraSettingsStore openCameraSettingsStore(Path projectRoot) {
        Path storagePath = projectRoot.resolve("config/data/camera_runtime_settings.json");
        try {
            return CameraSettingsStore.open(storagePath);
        } catch (IOException e) {
            log.warn("camera settings store unavailable path={}: {}", storagePath.toAbsolutePath(), e.getMessage());
            return null;
        }
    }

    private static LightBrightnessStore openLightBrightnessStore(Path projectRoot) {
        Path storagePath = projectRoot.resolve("config/data/light_brightness_settings.json");
        try {
            return LightBrightnessStore.open(storagePath);
        } catch (IOException e) {
            log.warn("light brightness store unavailable path={}: {}", storagePath.toAbsolutePath(), e.getMessage());
            return null;
        }
    }

    private static void applyPersistedLightBrightness(
            LightTriggerClient lightClient,
            LightBrightnessStore lightBrightnessStore
    ) {
        if (lightClient == null || lightBrightnessStore == null) {
            return;
        }
        LightBrightnessUpdate update = lightBrightnessStore.toUpdate();
        if (update.isEmpty()) {
            return;
        }
        try {
            lightClient.applyBrightnessUpdate(update);
            log.info(
                    "light persisted brightness applied default={} endpoints={}",
                    update.globalPercent(),
                    update.perEndpoint().size()
            );
        } catch (Exception e) {
            log.warn("light persisted brightness apply failed: {}", e.getMessage());
        }
    }

    private static void applyPersistedCameraSettings(
            Map<Integer, WorkerProcessSupervisor> workersByCamera,
            CameraSettingsStore cameraSettingsStore
    ) {
        if (cameraSettingsStore == null || workersByCamera == null || workersByCamera.isEmpty()) {
            return;
        }
        if (cameraSettingsStore.allSettings().isEmpty()) {
            return;
        }
        CameraWorkersHolder workersHolder = new CameraWorkersHolder();
        workersHolder.set(workersByCamera);
        new CameraSettingsService(workersHolder, null, cameraSettingsStore).applyPersistedSettings();
    }
}
