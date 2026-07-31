package com.example.iml.orchestrator.integration.bootstrap.context.impl;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.port.AbstractCameraRuntimeHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraInspectionLoopHost;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.pipeline.CameraInspectionDeps;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipeline;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Адаптер: per-camera inspection loops. */
public final class CameraInspectionLoopHostImpl extends AbstractCameraRuntimeHost implements CameraInspectionLoopHost {

    public CameraInspectionLoopHostImpl(CameraRuntimeContext runtime) {
        super(runtime);
    }

    @Override
    public Map<String, Object> integration() {
        return integrationMap();
    }

    @Override
    public Path projectRoot() {
        return projectRootPath();
    }

    @Override
    public IntegrationBootConfig bootConfig() {
        return bootCfg();
    }

    @Override
    public List<Map<String, Object>> activeCameras() {
        return workers().activeCameras();
    }

    @Override
    public Map<Integer, WorkerProcessSupervisor> workersByCamera() {
        return workers().workersByCamera();
    }

    @Override
    public InspectionPipeline inspectionPipeline() {
        return pipeline().inspectionPipeline();
    }

    @Override
    public InspectionTriggerStrategy sharedTriggerStrategy() {
        return triggers().sharedTriggerStrategy();
    }

    @Override
    public java.util.concurrent.ExecutorService cameraExecutor() {
        return stages().cameraExecutor();
    }

    @Override
    public CameraInspectionDeps createInspectionDeps() {
        Semaphore geometrySlots = new Semaphore(Math.max(1, processes().geometryPool().size()));
        Semaphore pythonSlots = new Semaphore(Math.max(1, processes().pythonPool().size()));
        PipelineReferenceRegistry registry = pipeline().pipelineReferenceRegistry();
        Map<Integer, ReferenceSnapshot> refs =
                registry != null ? registry.byCamera() : new ConcurrentHashMap<>();
        long inspectionCycleTimeoutMs = IntegrationFeatureConfig.parseInspectionCycleTimeoutMs(integration());
        return new CameraInspectionDeps(
                processes().pythonPool(),
                processes().geometryPool(),
                pipeline().lightClient(),
                processes().pythonCfg(),
                processes().geometryCfg(),
                health().fanOut(),
                geometrySlots,
                pythonSlots,
                new AtomicInteger(0),
                new AtomicInteger(0),
                refs,
                bootConfig().referenceSource(),
                bootConfig().reloadReference(),
                stages().captureStageExecutor(),
                stages().pythonStageExecutor(),
                stages().geometryStageExecutor(),
                stages().decisionStageExecutor(),
                ui().uiVisualsPython(),
                pipeline().flashLeadMs(),
                stages().pipelineStagesLog(),
                processes().inspectionGate(),
                inspectionCycleTimeoutMs,
                triggers().bucketInspectionAggregator()
        );
    }
}
