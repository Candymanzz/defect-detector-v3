package com.example.iml.orchestrator.integration.bootstrap.context.impl;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.port.AbstractCameraRuntimeHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraInspectionLoopHost;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipeline;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/** Адаптер: per-camera inspection loops. */
public final class CameraInspectionLoopHostImpl extends AbstractCameraRuntimeHost implements CameraInspectionLoopHost {

    public CameraInspectionLoopHostImpl(CameraRuntimeContext runtime) {
        super(runtime);
    }

    @Override
    public Map<String, Object> integration() {
        return preflight().integration();
    }

    @Override
    public Path projectRoot() {
        return env().projectRoot();
    }

    @Override
    public IntegrationBootConfig bootConfig() {
        return preflight().bootConfig();
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
    public List<BinaryRpcSupervisor> pythonPool() {
        return processes().pythonPool();
    }

    @Override
    public List<? extends ServiceProcessSupervisor> geometryPool() {
        return processes().geometryPool();
    }

    @Override
    public LightTriggerClient lightClient() {
        return pipeline().lightClient();
    }

    @Override
    public Map<String, Object> pythonCfg() {
        return processes().pythonCfg();
    }

    @Override
    public Map<String, Object> geometryCfg() {
        return processes().geometryCfg();
    }

    @Override
    public FanOutCoordinator fanOut() {
        return health().fanOut();
    }

    @Override
    public Map<Integer, ReferenceSnapshot> referenceByCamera() {
        return workers().referenceByCamera();
    }

    @Override
    public ExecutorService captureStageExecutor() {
        return stages().captureStageExecutor();
    }

    @Override
    public ExecutorService pythonStageExecutor() {
        return stages().pythonStageExecutor();
    }

    @Override
    public ExecutorService geometryStageExecutor() {
        return stages().geometryStageExecutor();
    }

    @Override
    public ExecutorService decisionStageExecutor() {
        return stages().decisionStageExecutor();
    }

    @Override
    public ExecutorService cameraExecutor() {
        return stages().cameraExecutor();
    }

    @Override
    public Map<String, Object> uiCfg() {
        return processes().uiCfg();
    }

    @Override
    public UiHttpServer uiServer() {
        return ui().uiServer();
    }

    @Override
    public BinaryRpcSupervisor uiVisualsPython() {
        return ui().uiVisualsPython();
    }

    @Override
    public ExecutorService uiArtifactsExecutor() {
        return ui().uiArtifactsExecutor();
    }

    @Override
    public InspectionTriggerStrategy sharedTriggerStrategy() {
        return triggers().sharedTriggerStrategy();
    }

    @Override
    public int flashLeadMs() {
        return pipeline().flashLeadMs();
    }

    @Override
    public PipelineStagesLog pipelineStagesLog() {
        return stages().pipelineStagesLog();
    }

    @Override
    public PerCameraInspectionGate inspectionGate() {
        return processes().inspectionGate();
    }

    @Override
    public BucketInspectionAggregator bucketInspectionAggregator() {
        return triggers().bucketInspectionAggregator();
    }

    @Override
    public InspectionPipeline inspectionPipeline() {
        return pipeline().inspectionPipeline();
    }
}
