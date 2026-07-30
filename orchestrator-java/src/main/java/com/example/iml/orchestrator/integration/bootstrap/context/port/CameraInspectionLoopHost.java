package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
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

/**
 * Порт запуска per-camera inspection loops.
 */
public interface CameraInspectionLoopHost {

    Map<String, Object> integration();

    Path projectRoot();

    IntegrationBootConfig bootConfig();

    List<Map<String, Object>> activeCameras();

    Map<Integer, WorkerProcessSupervisor> workersByCamera();

    List<BinaryRpcSupervisor> pythonPool();

    List<? extends ServiceProcessSupervisor> geometryPool();

    LightTriggerClient lightClient();

    Map<String, Object> pythonCfg();

    Map<String, Object> geometryCfg();

    FanOutCoordinator fanOut();

    Map<Integer, ReferenceSnapshot> referenceByCamera();

    ExecutorService captureStageExecutor();

    ExecutorService pythonStageExecutor();

    ExecutorService geometryStageExecutor();

    ExecutorService decisionStageExecutor();

    ExecutorService cameraExecutor();

    Map<String, Object> uiCfg();

    UiHttpServer uiServer();

    BinaryRpcSupervisor uiVisualsPython();

    ExecutorService uiArtifactsExecutor();

    InspectionTriggerStrategy sharedTriggerStrategy();

    int flashLeadMs();

    PipelineStagesLog pipelineStagesLog();

    PerCameraInspectionGate inspectionGate();

    BucketInspectionAggregator bucketInspectionAggregator();

    InspectionPipeline inspectionPipeline();
}
