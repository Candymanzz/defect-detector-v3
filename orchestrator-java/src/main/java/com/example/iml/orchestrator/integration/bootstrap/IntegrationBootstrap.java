package com.example.iml.orchestrator.integration.bootstrap;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationShutdownCoordinator;
import com.example.iml.orchestrator.integration.camera.WorkerIpcMode;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
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
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipeline;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.preview.LivePreviewPublisher;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.stream.ClientStreamConfig;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.pipeline.reference.ReferenceSnapshotBootstrap;
import com.example.iml.orchestrator.integration.pipeline.decision.DefaultInspectionDecisionAggregator;
import com.example.iml.orchestrator.integration.pipeline.fanoutbridge.InspectionDecisionToFanOutEvent;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectGeometryExecutor;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPythonExecutor;
import com.example.iml.orchestrator.integration.pipeline.stages.WorkerCaptureCoordinator;
import com.example.iml.orchestrator.integration.pipeline.telemetry.PipelineInspectionTelemetry;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerRuntime;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategy;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategyFactory;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import com.example.iml.orchestrator.integration.services.ServicePoolLifecycle;
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        IntegrationBootConfig cfg = IntegrationBootConfig.load(integration, cameras.size(), isWindows).withPoolCommands(List.of(), geometryCommand);
        PythonDetectorConfig pythonDetectorCfg = PythonDetectorConfig.fromRootYaml(root);

        ServicePoolLifecycle servicePools = new ServicePoolLifecycle(log);
        AnalisSurfaceLauncher analisSurfaceLauncher = new AnalisSurfaceLauncher(log);
        LightServerLauncher lightServerLauncher = new LightServerLauncher(log);
        UiArtifactsSidecar uiSidecar = new UiArtifactsSidecar(log);
        GeometrySnapshotCache geometrySnapshotCache = new GeometrySnapshotCache();
        GeometryRuntimeConfig geometryRuntimeConfig = new GeometryRuntimeConfig();
        ClientApiMount clientApiMount = ClientApiMount.fromRootYaml(root, geometryRuntimeConfig);
        FrameJpegWriter jpegWriter = new FrameJpegWriter(log);
        WorkerCaptureCoordinator captureCoordinator = new WorkerCaptureCoordinator(log, jpegWriter);
        PipelineInspectionTelemetry pipelineTelemetry = new PipelineInspectionTelemetry();
        ReferenceSnapshotBootstrap referenceBootstrap = new ReferenceSnapshotBootstrap(log, captureCoordinator, pipelineTelemetry);
        InspectionPipelineServices pipelineServices = new InspectionPipelineServices(
                log,
                new DefaultInspectionDecisionAggregator(log),
                pipelineTelemetry,
                new InspectGeometryExecutor(log, geometrySnapshotCache, geometryRuntimeConfig),
                new InspectPythonExecutor(log, geometryRuntimeConfig),
                captureCoordinator,
                new InspectionDecisionToFanOutEvent(),
                referenceBootstrap,
                uiSidecar
        );
        InspectionPipeline inspectionPipeline = new InspectionPipeline(pipelineServices);

        ExternalServiceProcess analisSurfaceProcess = analisSurfaceLauncher.startIfConfigured(
                integration,
                projectRoot,
                isWindows,
                pythonDetectorCfg.baseUrl()
        );

        List<BinaryRpcSupervisor> pythonPool = servicePools.startAnalisSurfaceHttpPool(
                pythonDetectorCfg.baseUrl(),
                cfg.pythonParallelism(),
                cfg.serviceCommandTimeoutMs()
        );
        if (pythonPool.isEmpty()) {
            log.error(
                    "analisSurface FastAPI pool is empty (base_url={}). "
                            + "Проверьте venv в analisSurface/backend, integration.analis_surface_autostart и python_detector.base_url.",
                    pythonDetectorCfg.baseUrl()
            );
            if (analisSurfaceProcess != null) {
                analisSurfaceProcess.close();
            }
            return;
        }
        log.info("python detector transport=http base_url={} pool_size={}", pythonDetectorCfg.baseUrl(), pythonPool.size());
        List<ServiceProcessSupervisor> geometryPool = servicePools.startOptionalPool(
                geometryCommand,
                projectRoot,
                "java-geometry",
                cfg.serviceCommandTimeoutMs(),
                cfg.geometryPoolSize()
        );
        ExternalServiceProcess lightServerProcess = lightServerLauncher.startIfConfigured(
                integration, projectRoot, isWindows, cfg.lightStartupDelayMs());
        @SuppressWarnings("unchecked")
        Map<String, Object> pythonCfg = (Map<String, Object>) root.get("python_detector");
        @SuppressWarnings("unchecked")
        Map<String, Object> geometryCfg = (Map<String, Object>) root.get("java_geometry");
        @SuppressWarnings("unchecked")
        Map<String, Object> uiCfg = (Map<String, Object>) root.get("ui_http");
        int flashLeadMs = LightServersConfig.flashLeadMsFromRoot(root);
        if (flashLeadMs > 0) {
            log.info("light_servers flash_lead_ms={} (пауза после старта POST вспышки, перед capture)", flashLeadMs);
        }
        LightTriggerClient lightClient = LightTriggerClient.fromRootYaml(root);
        if (lightClient.isEnabled()) {
            log.info("waiting for LightServer COM bank (GET /api/com/light)...");
            lightClient.awaitEndpointsReady();
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
        final UiHttpServer uiServer = uiSidecar.startHttpServerIfEnabled(
                uiCfg, geometrySnapshotCache, clientApiMount, lightClient, root);
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
            clientWsServer.setLightTriggerClient(lightClient);
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
        CameraStreamService cameraStreamService = null;
        InspectionTriggerRuntime triggerRuntime = null;
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
            FanOutCoordinator activeFanOut = FanOutCoordinator.fromConfig(root);
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
            Map<Integer, String> productTypeByCamera = new LinkedHashMap<>();
            for (Map<String, Object> camera : activeCameras) {
                int cameraId = ((Number) camera.get("id")).intValue();
                productTypeByCamera.put(cameraId, String.valueOf(camera.getOrDefault("product_type", "camera-" + cameraId)));
            }
            ClientStreamConfig clientStreamCfg = ClientStreamConfig.fromRootYaml(root);
            if (uiServer != null && !workersByCamera.isEmpty()) {
                cameraStreamService = new CameraStreamService(
                        log,
                        clientStreamCfg,
                        workersByCamera,
                        productTypeByCamera,
                        detectorByCamera,
                        uiServer,
                        clientWsServer,
                        uiCfg
                );
                captureCoordinator.setCameraStreamService(cameraStreamService);
                if (clientWsServer != null) {
                    clientWsServer.setCameraStreamService(cameraStreamService);
                    clientWsServer.setClientStreamConfig(clientStreamCfg);
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
                    cameraStreamService
            );
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
            IntegrationFeatureConfig.SingleFrameBenchmarkConfig singleFrameBenchmark = IntegrationFeatureConfig.parseSingleFrameBenchmark(integration);
            IntegrationFeatureConfig.ConveyorBenchmarkConfig conveyorBenchmark = IntegrationFeatureConfig.parseConveyorBenchmark(integration);
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection =
                    IntegrationFeatureConfig.parseContinuousInspection(integration);
            IntegrationFeatureConfig.InspectionTriggerMode triggerMode =
                    IntegrationFeatureConfig.resolveInspectionTriggerMode(integration);
        triggerRuntime = InspectionTriggerRuntime.start(
                log,
                integration,
                workersByCamera.keySet(),
                triggerMode
        );
        InspectionTriggerStrategy sharedTriggerStrategy = InspectionTriggerStrategyFactory.create(
                    triggerMode,
                    triggerRuntime.bus(),
                    devAutoTriggerStub,
                    continuousInspection
            );
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures = IntegrationFeatureConfig.parseSaveCaptures(integration);
            if (saveCaptures.enabled()) {
                log.info("save_captures enabled dir={} (от корня проекта)", saveCaptures.relativeDir());
            }
            if (conveyorBenchmark.enabled()) {
                log.info("conveyor_benchmark enabled buckets={} photos_per_bucket={} reference_repeats={} cycle_delay_ms={} prefix={}",
                        conveyorBenchmark.buckets(),
                        conveyorBenchmark.photosPerBucket(),
                        conveyorBenchmark.referenceRepeats(),
                        conveyorBenchmark.cycleDelayMs(),
                        conveyorBenchmark.productTypePrefix());
            } else if (singleFrameBenchmark.enabled()) {
                log.info("single_frame_benchmark enabled reference_repeats={} inspection_repeats={}",
                        singleFrameBenchmark.referenceRepeats(), singleFrameBenchmark.inspectionRepeats());
            } else if (devAutoTriggerStub.enabled()) {
                log.info("dev_auto_trigger_stub enabled interval_ms={}", devAutoTriggerStub.intervalMs());
            } else if (continuousInspection.enabled()) {
                log.info("continuous_inspection enabled cycle_delay_ms={}", continuousInspection.cycleDelayMs());
            } else if (triggerMode == IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL) {
                InspectionTriggerConfig triggerCfg = InspectionTriggerConfig.parse(integration);
                if (triggerCfg.udp().enabled()) {
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
            List<Callable<Void>> tasks = new ArrayList<>();
            for (Map<String, Object> camera : activeCameras) {
                tasks.add(() -> {
                    int cameraId = ((Number) camera.get("id")).intValue();
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
                            singleFrameBenchmark,
                            conveyorBenchmark,
                            continuousInspection,
                            sharedTriggerStrategy,
                            triggerMode,
                            saveCaptures,
                            flashLeadMs,
                            pipelineStagesLog
                    );
                    return null;
                });
            }
            List<Future<Void>> futures = cameraExecutor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (Exception e) {
            log.error("Integration bootstrap failed", e);
        } finally {
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
                    analisSurfaceProcess,
                    lightClient,
                    uiVisualsPython,
                    uiArtifactsExecutor,
                    fanOut,
                    clientWsServer,
                    uiServer,
                    servicePools,
                    log
            ));
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
}
