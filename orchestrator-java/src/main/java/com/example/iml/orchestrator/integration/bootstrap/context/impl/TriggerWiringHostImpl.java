package com.example.iml.orchestrator.integration.bootstrap.context.impl;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.port.AbstractCameraRuntimeHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerWiringHost;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
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

/** Адаптер: triggers / line-sync / bucket. */
public final class TriggerWiringHostImpl extends AbstractCameraRuntimeHost implements TriggerWiringHost {

    public TriggerWiringHostImpl(CameraRuntimeContext runtime) {
        super(runtime);
    }

    @Override
    public Map<String, Object> root() {
        return env().root();
    }

    @Override
    public Map<String, Object> integration() {
        return preflight().integration();
    }

    @Override
    public Map<String, Object> geometryCfg() {
        return processes().geometryCfg();
    }

    @Override
    public IntegrationBootConfig bootConfig() {
        return preflight().bootConfig();
    }

    @Override
    public Map<Integer, WorkerProcessSupervisor> workersByCamera() {
        return workers().workersByCamera();
    }

    @Override
    public PerCameraInspectionGate inspectionGate() {
        return processes().inspectionGate();
    }

    @Override
    public WorkerCaptureCoordinator captureCoordinator() {
        return processes().captureCoordinator();
    }

    @Override
    public ManualLineDirectionService manualLineDirection() {
        return processes().manualLineDirection();
    }

    @Override
    public LightTriggerClient lightClient() {
        return pipeline().lightClient();
    }

    @Override
    public FanOutCoordinator fanOut() {
        return health().fanOut();
    }

    @Override
    public OrchestratorStopSignal stopSignal() {
        return health().stopSignal();
    }

    @Override
    public LineSynchronizedCaptureCoordinator lineCaptureCoordinator() {
        return triggers().lineCaptureCoordinator();
    }

    @Override
    public void setLineCaptureCoordinator(LineSynchronizedCaptureCoordinator coordinator) {
        triggers().setLineCaptureCoordinator(coordinator);
    }

    @Override
    public InspectionTriggerRuntime triggerRuntime() {
        return triggers().triggerRuntime();
    }

    @Override
    public void setTriggerRuntime(InspectionTriggerRuntime triggerRuntime) {
        triggers().setTriggerRuntime(triggerRuntime);
    }

    @Override
    public void setBucketInspectionAggregator(BucketInspectionAggregator aggregator) {
        triggers().setBucketInspectionAggregator(aggregator);
    }

    @Override
    public void setBucketLineTriggerBroadcaster(BucketLineTriggerBroadcaster broadcaster) {
        triggers().setBucketLineTriggerBroadcaster(broadcaster);
    }

    @Override
    public void setIntervalFlashController(IntervalFlashController controller) {
        triggers().setIntervalFlashController(controller);
    }

    @Override
    public void setSharedTriggerStrategy(InspectionTriggerStrategy strategy) {
        triggers().setSharedTriggerStrategy(strategy);
    }
}
