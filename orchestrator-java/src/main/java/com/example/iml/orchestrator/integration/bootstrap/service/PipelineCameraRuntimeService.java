package com.example.iml.orchestrator.integration.bootstrap.service;

import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.factory.IntegrationServicePoolFactory;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationLifecycleComposite;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.ImlShmJanitor;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.health.CriticalServiceWatchdog;
import com.example.iml.orchestrator.integration.health.ServiceHealthGate;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionConfig;
import com.example.iml.orchestrator.integration.preview.LivePreviewPublisher;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime try-block: SHM janitor, fan-out, workers, preview, triggers, blocking camera pipelines.
 */
public final class PipelineCameraRuntimeService {

    private final Logger log;
    private final CameraWorkerBootstrapService workers;
    private final TriggerRuntimeBootstrapService triggers;

    public PipelineCameraRuntimeService(Logger log) {
        this.log = log;
        this.workers = new CameraWorkerBootstrapService(log);
        this.triggers = new TriggerRuntimeBootstrapService(log);
    }

    /**
     * @return {@code false} если workers не стартовали (early return из try)
     */
    public boolean runBlocking(
            IntegrationRuntimeContext ctx,
            IntegrationServicePoolFactory poolFactory,
            IntegrationLifecycleComposite lifecycle
    ) throws Exception {
        startShmJanitor(ctx);
        startTimingStagesLog(ctx);

        FanOutCoordinator fanOut = FanOutCoordinator.fromConfig(
                ctx.root(),
                ctx.projectRoot(),
                ctx.clientWsServer(),
                ctx.inspectionGate()
        );
        ctx.setFanOut(fanOut);
        ctx.plcFinsHolder().set(fanOut);

        ServiceHealthGate healthGate = new ServiceHealthGate();
        ctx.setServiceHealthGate(healthGate);
        fanOut.setHealthGate(healthGate);

        OrchestratorStopSignal stopSignal = new OrchestratorStopSignal();
        ctx.setStopSignal(stopSignal);
        if (ctx.frontendProcess() != null) {
            ctx.frontendProcess().onUnexpectedExit(() -> {
                log.warn("frontend process exited — requesting orchestrator shutdown (vision_ready=0)");
                stopSignal.request("frontend_exited");
            });
        }

        if (ctx.clientWsServer() != null) {
            ctx.clientWsServer().setSessionStateListener(fanOut::onSessionState);
            fanOut.onSessionState(ctx.clientWsServer().sessionState());
        }
        log.info(
                "integration parallel settings: camera_parallelism={} geometry_pool_size={}",
                ctx.bootConfig().cameraParallelism(),
                ctx.geometryPool().size()
        );

        if (!workers.startWorkers(ctx)) {
            return false;
        }
        workers.attachStreamService(ctx);

        CriticalServiceWatchdog watchdog = CriticalServiceWatchdog.start(log, ctx, healthGate);
        ctx.setCriticalServiceWatchdog(watchdog);

        IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub =
                IntegrationFeatureConfig.parseDevAutoTriggerStub(ctx.integration());
        LivePreviewPublisher livePreview = LivePreviewPublisher.start(
                log,
                ctx.root(),
                ctx.activeCameras(),
                ctx.workersByCamera(),
                ctx.lightClient(),
                ctx.uiServer(),
                ctx.clientWsServer(),
                ctx.flashLeadMs(),
                ctx.uiCfg(),
                ctx.bootConfig().referenceSource(),
                ctx.pipelineReferenceRegistry(),
                devAutoTriggerStub,
                ctx.cameraStreamService(),
                ctx.livePreviewGate(),
                ctx.inspectionGate()
        );
        ctx.setLivePreview(livePreview);
        if (livePreview != null && ctx.lineCaptureCoordinator() != null) {
            livePreview.setLineCaptureCoordinator(ctx.lineCaptureCoordinator());
        }

        createStageExecutors(ctx, poolFactory);

        TriggerRuntimeBootstrapService.TriggerWireResult triggerWire = triggers.wire(ctx);
        if (livePreview != null && ctx.lineCaptureCoordinator() != null) {
            livePreview.setLineCaptureCoordinator(ctx.lineCaptureCoordinator());
        }

        lifecycle.registerAll(ctx.managedRuntimeComponents());
        // Components already started by owning services; close-only composite.
        lifecycle.start();

        runCameraTasks(ctx, triggerWire, stopSignal);
        return true;
    }

    private void startShmJanitor(IntegrationRuntimeContext ctx) {
        var shmJanitorScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "iml-shm-janitor");
            t.setDaemon(true);
            return t;
        });
        ctx.setShmJanitorScheduler(shmJanitorScheduler);
        shmJanitorScheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        ImlShmJanitor.purgeEphemeralOlderThan(ImlShmJanitor.DEFAULT_EPHEMERAL_TTL, log);
                    } catch (Exception e) {
                        log.warn("iml_shm ttl janitor failed: {}", e.getMessage());
                    }
                },
                15L,
                15L,
                TimeUnit.SECONDS
        );
        log.info(
                "iml_shm ttl janitor started interval_s=15 max_age_s={}",
                ImlShmJanitor.DEFAULT_EPHEMERAL_TTL.toSeconds()
        );
    }

    private void startTimingStagesLog(IntegrationRuntimeContext ctx) {
        IntegrationFeatureConfig.TimingStagesLogConfig timingStagesLogCfg =
                IntegrationFeatureConfig.parseTimingStagesLog(ctx.integration());
        if (!timingStagesLogCfg.enabled()) {
            return;
        }
        try {
            Path timingPath = ctx.projectRoot().resolve(timingStagesLogCfg.relativePath());
            ctx.setPipelineStagesLog(new PipelineStagesLog(timingPath));
            log.info("timing_stages_log enabled jsonl={} (рядом .txt с тем же базовым именем)", timingPath);
        } catch (Exception e) {
            log.warn("timing_stages_log init failed: {}", e.getMessage());
        }
    }

    private void createStageExecutors(IntegrationRuntimeContext ctx, IntegrationServicePoolFactory poolFactory) {
        var cfg = ctx.bootConfig();
        ctx.setCameraExecutor(Executors.newFixedThreadPool(cfg.cameraParallelism(), r -> {
            Thread t = new Thread(r, "camera-flow");
            t.setDaemon(true);
            return t;
        }));
        ctx.setCaptureStageExecutor(poolFactory.createStageExecutor("stage-capture", cfg.cameraParallelism(), cfg.stageQueueSize()));
        ctx.setPythonStageExecutor(poolFactory.createStageExecutor("stage-python", cfg.pythonParallelism(), cfg.stageQueueSize()));
        ctx.setGeometryStageExecutor(poolFactory.createStageExecutor(
                "stage-geometry", Math.max(1, ctx.geometryPool().size()), cfg.stageQueueSize()));
        ctx.setDecisionStageExecutor(poolFactory.createStageExecutor("stage-decision", cfg.cameraParallelism(), cfg.stageQueueSize()));
        log.info("pipeline settings: queue_size={} python_parallelism={}", cfg.stageQueueSize(), cfg.pythonParallelism());
    }

    private void runCameraTasks(
            IntegrationRuntimeContext ctx,
            TriggerRuntimeBootstrapService.TriggerWireResult triggerWire,
            OrchestratorStopSignal stopSignal
    ) throws Exception {
        Semaphore geometrySlots = new Semaphore(Math.max(1, ctx.geometryPool().size()));
        Semaphore pythonSlots = new Semaphore(Math.max(1, ctx.pythonPool().size()));
        AtomicInteger geometryRoundRobin = new AtomicInteger(0);
        AtomicInteger pythonRoundRobin = new AtomicInteger(0);

        IntegrationFeatureConfig.SaveCapturesConfig saveCaptures =
                IntegrationFeatureConfig.parseSaveCaptures(ctx.integration());
        int inspectionCycleTimeoutMs = IntegrationFeatureConfig.parseInspectionCycleTimeoutMs(ctx.integration());
        boolean captureWithoutReference = IntegrationFeatureConfig.parseCaptureWithoutReference(ctx.integration());
        if (captureWithoutReference) {
            log.info("integration capture_without_reference enabled — trigger capture without client.reference_bundle");
        }
        log.info(
                "inspection gate per-camera in-flight enabled timeout_ms={} cameras={}",
                inspectionCycleTimeoutMs,
                ctx.workersByCamera().keySet()
        );

        BucketInspectionConfig bucketInspectionConfig =
                BucketInspectionConfig.parse(ctx.integration(), ctx.workersByCamera().keySet());
        Set<Integer> activeInspectionCameraIds = Set.copyOf(triggerWire.inspectionCameraIds());
        BucketInspectionAggregator activeBucketAggregator = ctx.bucketInspectionAggregator();
        PipelineStagesLog pipelineStagesLog = ctx.pipelineStagesLog();
        FanOutCoordinator activeFanOut = ctx.fanOut();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (Map<String, Object> camera : ctx.activeCameras()) {
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
                WorkerProcessSupervisor worker = ctx.workersByCamera().get(cameraId);
                if (worker == null) {
                    log.warn("camera task skipped: worker not initialized for camera {}", cameraId);
                    return null;
                }
                ctx.inspectionPipeline().processCamera(
                        ctx.projectRoot(),
                        camera,
                        worker,
                        ctx.pythonPool(),
                        ctx.geometryPool(),
                        ctx.lightClient(),
                        ctx.pythonCfg(),
                        ctx.geometryCfg(),
                        activeFanOut,
                        geometrySlots,
                        pythonSlots,
                        geometryRoundRobin,
                        pythonRoundRobin,
                        ctx.referenceByCamera(),
                        ctx.bootConfig().referenceSource(),
                        ctx.bootConfig().reloadReference(),
                        ctx.captureStageExecutor(),
                        ctx.pythonStageExecutor(),
                        ctx.geometryStageExecutor(),
                        ctx.decisionStageExecutor(),
                        ctx.uiCfg(),
                        ctx.uiServer(),
                        ctx.uiVisualsPython(),
                        ctx.uiArtifactsExecutor(),
                        ctx.sharedTriggerStrategy(),
                        triggerWire.triggerMode(),
                        saveCaptures,
                        ctx.flashLeadMs(),
                        pipelineStagesLog,
                        ctx.inspectionGate(),
                        inspectionCycleTimeoutMs,
                        activeBucketAggregator,
                        captureWithoutReference
                );
                return null;
            });
        }
        triggerWire.softwareVisionReady().set(true);
        triggerWire.refreshVisionReady().run();
        List<Future<Void>> futures = new ArrayList<>(tasks.size());
        for (Callable<Void> task : tasks) {
            futures.add(ctx.cameraExecutor().submit(task));
        }
        awaitCameraTasksOrStop(futures, stopSignal);
    }

    private void awaitCameraTasksOrStop(List<Future<Void>> futures, OrchestratorStopSignal stopSignal)
            throws Exception {
        while (true) {
            if (stopSignal != null && stopSignal.isRequested()) {
                log.warn("orchestrator stop requested reason={} — cancelling camera tasks", stopSignal.reason());
                for (Future<Void> future : futures) {
                    future.cancel(true);
                }
                return;
            }
            boolean allDone = true;
            for (Future<Void> future : futures) {
                if (!future.isDone()) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) {
                for (Future<Void> future : futures) {
                    try {
                        future.get();
                    } catch (CancellationException ignored) {
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof Exception ex) {
                            throw ex;
                        }
                        throw e;
                    }
                }
                return;
            }
            if (stopSignal == null) {
                // No stop signal (no frontend): block on first unfinished future.
                for (Future<Void> future : futures) {
                    try {
                        future.get();
                    } catch (CancellationException ignored) {
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof Exception ex) {
                            throw ex;
                        }
                        throw e;
                    }
                }
                return;
            }
            stopSignal.await(250, TimeUnit.MILLISECONDS);
        }
    }
}
