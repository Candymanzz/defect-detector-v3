package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.pipeline.CameraInspectionDeps;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipeline;
import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Порт запуска per-camera inspection loops.
 */
public interface CameraInspectionLoopHost {

    Map<String, Object> integration();

    Path projectRoot();

    IntegrationBootConfig bootConfig();

    List<Map<String, Object>> activeCameras();

    Map<Integer, WorkerProcessSupervisor> workersByCamera();

    InspectionPipeline inspectionPipeline();

    InspectionTriggerStrategy sharedTriggerStrategy();

    java.util.concurrent.ExecutorService cameraExecutor();

    /** Shared deps for all camera tasks (slots / round-robin / pools / lighting / refs). */
    CameraInspectionDeps createInspectionDeps();
}
