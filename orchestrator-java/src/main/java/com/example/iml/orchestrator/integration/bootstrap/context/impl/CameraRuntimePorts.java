package com.example.iml.orchestrator.integration.bootstrap.context.impl;

import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraInspectionLoopHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraWorkerHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CriticalWatchdogHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.FanOutHealthHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.LivePreviewHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.RuntimeMaintenanceHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.StageExecutorHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerWiringHost;

import java.util.Objects;

/**
 * Фабрика узких адаптеров над одним {@link CameraRuntimeContext}.
 */
public final class CameraRuntimePorts {

    private final RuntimeMaintenanceHost maintenance;
    private final FanOutHealthHost fanOutHealth;
    private final CameraWorkerHost cameraWorkers;
    private final CriticalWatchdogHost criticalWatchdog;
    private final LivePreviewHost livePreview;
    private final StageExecutorHost stageExecutors;
    private final TriggerWiringHost triggerWiring;
    private final CameraInspectionLoopHost inspectionLoops;

    private CameraRuntimePorts(CameraRuntimeContext runtime) {
        Objects.requireNonNull(runtime, "runtime");
        this.maintenance = new RuntimeMaintenanceHostImpl(runtime);
        this.fanOutHealth = new FanOutHealthHostImpl(runtime);
        this.cameraWorkers = new CameraWorkerHostImpl(runtime);
        this.criticalWatchdog = new CriticalWatchdogHostImpl(runtime);
        this.livePreview = new LivePreviewHostImpl(runtime);
        this.stageExecutors = new StageExecutorHostImpl(runtime);
        this.triggerWiring = new TriggerWiringHostImpl(runtime);
        this.inspectionLoops = new CameraInspectionLoopHostImpl(runtime);
    }

    public static CameraRuntimePorts of(CameraRuntimeContext runtime) {
        return new CameraRuntimePorts(runtime);
    }

    public RuntimeMaintenanceHost maintenance() {
        return maintenance;
    }

    public FanOutHealthHost fanOutHealth() {
        return fanOutHealth;
    }

    public CameraWorkerHost cameraWorkers() {
        return cameraWorkers;
    }

    public CriticalWatchdogHost criticalWatchdog() {
        return criticalWatchdog;
    }

    public LivePreviewHost livePreview() {
        return livePreview;
    }

    public StageExecutorHost stageExecutors() {
        return stageExecutors;
    }

    public TriggerWiringHost triggerWiring() {
        return triggerWiring;
    }

    public CameraInspectionLoopHost inspectionLoops() {
        return inspectionLoops;
    }
}
