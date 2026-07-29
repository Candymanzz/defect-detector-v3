package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.lighting.IntervalFlashController;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.pipeline.stages.WorkerCaptureCoordinator;
import com.example.iml.orchestrator.integration.trigger.BucketLineTriggerBroadcaster;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerRuntime;
import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;

import java.util.Map;

/**
 * Порт wiring triggers / line-sync / bucket / interval flash.
 */
public interface TriggerWiringHost {

    Map<String, Object> root();

    Map<String, Object> integration();

    Map<String, Object> geometryCfg();

    IntegrationBootConfig bootConfig();

    Map<Integer, WorkerProcessSupervisor> workersByCamera();

    PerCameraInspectionGate inspectionGate();

    WorkerCaptureCoordinator captureCoordinator();

    ManualLineDirectionService manualLineDirection();

    LightTriggerClient lightClient();

    LineSynchronizedCaptureCoordinator lineCaptureCoordinator();

    void setLineCaptureCoordinator(LineSynchronizedCaptureCoordinator coordinator);

    InspectionTriggerRuntime triggerRuntime();

    void setTriggerRuntime(InspectionTriggerRuntime triggerRuntime);

    void setBucketInspectionAggregator(BucketInspectionAggregator aggregator);

    void setBucketLineTriggerBroadcaster(BucketLineTriggerBroadcaster broadcaster);

    void setIntervalFlashController(IntervalFlashController controller);

    void setSharedTriggerStrategy(InspectionTriggerStrategy strategy);
}
