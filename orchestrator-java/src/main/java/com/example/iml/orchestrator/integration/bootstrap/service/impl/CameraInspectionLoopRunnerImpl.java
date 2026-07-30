package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.BootstrapInspectionFeatures;

import com.example.iml.orchestrator.integration.bootstrap.service.api.CameraInspectionLoopRunner;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;
import com.example.iml.orchestrator.integration.bootstrap.service.api.TriggerRuntimeBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraInspectionLoopHost;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionConfig;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Только submit/await camera inspection tasks.
 */
public final class CameraInspectionLoopRunnerImpl extends AbstractBootstrapService
        implements CameraInspectionLoopRunner {

    public CameraInspectionLoopRunnerImpl(Logger log) {
        super(log);
    }

    @Override
    public void runBlocking(
            CameraInspectionLoopHost ctx,
            TriggerRuntimeBootstrap.TriggerWireResult triggerWire,
            OrchestratorStopSignal stopSignal
    ) throws Exception {
        Semaphore geometrySlots = new Semaphore(Math.max(1, ctx.geometryPool().size()));
        Semaphore pythonSlots = new Semaphore(Math.max(1, ctx.pythonPool().size()));
        AtomicInteger geometryRoundRobin = new AtomicInteger(0);
        AtomicInteger pythonRoundRobin = new AtomicInteger(0);

        IntegrationFeatureConfig.SaveCapturesConfig saveCaptures =
                BootstrapInspectionFeatures.saveCaptures(ctx.integration());
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
                BootstrapInspectionFeatures.bucketInspection(ctx.integration(), ctx.workersByCamera().keySet());
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
