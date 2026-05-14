package com.example.iml.orchestrator.integration.bootstrap;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationShutdownCoordinator;
import com.example.iml.orchestrator.integration.camera.WorkerIpcMode;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.CameraWorkerPaths;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.lighting.LightServerLauncher;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipeline;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.reference.ReferenceSnapshotBootstrap;
import com.example.iml.orchestrator.integration.pipeline.decision.DefaultInspectionDecisionAggregator;
import com.example.iml.orchestrator.integration.pipeline.fanoutbridge.InspectionDecisionToFanOutEvent;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectGeometryExecutor;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPythonExecutor;
import com.example.iml.orchestrator.integration.pipeline.stages.WorkerCaptureCoordinator;
import com.example.iml.orchestrator.integration.pipeline.telemetry.PipelineInspectionTelemetry;
import com.example.iml.orchestrator.integration.services.ServicePoolLifecycle;
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.clientws.ClientWsConfig;
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
import java.util.concurrent.ConcurrentHashMap;
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
        List<Map<String, Object>> cameras = (List<Map<String, Object>>) root.get("cameras");
        if (cameras == null || cameras.isEmpty()) {
            log.warn("No cameras in config; integration pipeline skipped");
            return;
        }

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

        List<String> pythonCommand = CameraWorkerPaths.pickIntegrationCommandList(integration, isWindows, "python_command_windows", "python_command_linux");
        List<String> geometryCommand = CameraWorkerPaths.pickIntegrationCommandList(integration, isWindows, "geometry_command_windows", "geometry_command_linux");
        IntegrationBootConfig cfg = IntegrationBootConfig.load(integration, cameras.size(), isWindows).withPoolCommands(pythonCommand, geometryCommand);

        ServicePoolLifecycle servicePools = new ServicePoolLifecycle(log);
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
                new InspectPythonExecutor(log),
                captureCoordinator,
                new InspectionDecisionToFanOutEvent(),
                referenceBootstrap,
                uiSidecar
        );
        InspectionPipeline inspectionPipeline = new InspectionPipeline(pipelineServices);

        List<ServiceProcessSupervisor> pythonPool = servicePools.startOptionalPool(
                pythonCommand,
                projectRoot,
                "detector-stdio",
                cfg.serviceCommandTimeoutMs(),
                cfg.pythonParallelism()
        );
        List<ServiceProcessSupervisor> geometryPool = servicePools.startOptionalPool(
                geometryCommand,
                projectRoot,
                "java-geometry",
                cfg.serviceCommandTimeoutMs(),
                cfg.geometryPoolSize()
        );
        ExternalServiceProcess lightServerProcess = lightServerLauncher.startIfConfigured(integration, projectRoot, isWindows, cfg.lightStartupDelayMs());
        @SuppressWarnings("unchecked")
        Map<String, Object> pythonCfg = (Map<String, Object>) root.get("python_detector");
        @SuppressWarnings("unchecked")
        Map<String, Object> geometryCfg = (Map<String, Object>) root.get("java_geometry");
        @SuppressWarnings("unchecked")
        Map<String, Object> uiCfg = (Map<String, Object>) root.get("ui_http");
        @SuppressWarnings("unchecked")
        Map<String, Object> lightCfg = (Map<String, Object>) root.get("light_server");
        int flashLeadMs = Math.max(0, YamlScalars.toInt(lightCfg == null ? null : lightCfg.get("flash_lead_ms"), 0));
        if (flashLeadMs > 0) {
            log.info("light_server flash_lead_ms={} (пауза после ответа вспышки, перед capture)", flashLeadMs);
        }
        LightTriggerClient lightClient = LightTriggerClient.fromLightServerYaml(lightCfg);

        ClientWebSocketServer clientWsServer = null;
        final UiHttpServer uiServer = uiSidecar.startHttpServerIfEnabled(uiCfg, geometrySnapshotCache, clientApiMount);
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
        }
        uiSidecar.setClientWebSocketServer(clientWsServer);
        if (clientApiMount.enabled()) {
            log.info(
                    "client_api enabled (same port as ui_http): kopcheni_proxy={} kopcheni_base_url={}",
                    clientApiMount.kopcheniConfigured(),
                    clientApiMount.kopcheniBaseUrl()
            );
        }
        final ServiceProcessSupervisor uiVisualsPython = uiSidecar.startVisualsPythonIfEnabled(
                uiCfg,
                projectRoot,
                cfg.serviceCommandTimeoutMs(),
                pythonCommand
        );
        final ExecutorService uiArtifactsExecutor = uiSidecar.startUiPublishExecutorIfEnabled(uiCfg);
        FanOutCoordinator fanOut = null;
        ExecutorService cameraExecutor = null;
        ExecutorService captureStageExecutor = null;
        ExecutorService pythonStageExecutor = null;
        ExecutorService geometryStageExecutor = null;
        ExecutorService decisionStageExecutor = null;
        Map<Integer, WorkerProcessSupervisor> workersByCamera = new LinkedHashMap<>();
        Map<Integer, ReferenceSnapshot> referenceByCamera = new ConcurrentHashMap<>();
        PipelineStagesLog pipelineStagesLogMutable = null;
        Path workerConfigPath = CameraWorkerPaths.resolveWorkerConfigPath(projectRoot, integration);
        if (!Files.isRegularFile(workerConfigPath)) {
            log.error("Файл конфигурации camera-worker не найден: {} (integration.worker_config_json)", workerConfigPath.toAbsolutePath());
        } else {
            log.info("camera_worker config={}", workerConfigPath.toAbsolutePath());
        }

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
                WorkerProcessSupervisor worker = new WorkerProcessSupervisor(
                        cameraId, cmd, projectRoot, cfg.workerIpcMode(), workerPipePath, cfg.workerPipeConnectTimeoutMs(), cfg.workerCommandTimeoutMs());
                worker.start();
                BinaryProtocol.Message health = worker.health();
                log.info("worker cam={} health type={} header={}", cameraId, health.type(), health.header());
                workersByCamera.put(cameraId, worker);
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
            IntegrationFeatureConfig.SingleFrameBenchmarkConfig singleFrameBenchmark = IntegrationFeatureConfig.parseSingleFrameBenchmark(integration);
            IntegrationFeatureConfig.ConveyorBenchmarkConfig conveyorBenchmark = IntegrationFeatureConfig.parseConveyorBenchmark(integration);
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection = IntegrationFeatureConfig.parseContinuousInspection(integration);
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
            } else if (continuousInspection.enabled()) {
                log.info("continuous_inspection enabled cycle_delay_ms={}", continuousInspection.cycleDelayMs());
            }
            List<Callable<Void>> tasks = new ArrayList<>();
            for (Map<String, Object> camera : cameras) {
                tasks.add(() -> {
                    int cameraId = ((Number) camera.get("id")).intValue();
                    WorkerProcessSupervisor worker = workersByCamera.get(cameraId);
                    if (worker == null) {
                        throw new IllegalStateException("worker not initialized for camera " + cameraId);
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
}
