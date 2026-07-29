package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.CameraRuntimeBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;
import com.example.iml.orchestrator.integration.bootstrap.service.api.CameraInspectionLoopRunner;
import com.example.iml.orchestrator.integration.bootstrap.service.api.CameraWorkerBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.api.CriticalWatchdogBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.api.FanOutHealthBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.api.LivePreviewBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.api.RuntimeMaintenanceBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.api.StageExecutorBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.api.TriggerRuntimeBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.impl.CameraRuntimePorts;
import com.example.iml.orchestrator.integration.bootstrap.factory.IntegrationServicePoolFactory;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationLifecycleComposite;
import org.apache.logging.log4j.Logger;

/**
 * Координатор camera-runtime: сервисы получают узкие адаптеры, не весь context.
 */
public final class CameraRuntimeBootstrapImpl extends AbstractBootstrapService implements CameraRuntimeBootstrap {

    private final RuntimeMaintenanceBootstrap maintenance;
    private final FanOutHealthBootstrap fanOutHealth;
    private final CameraWorkerBootstrap workers;
    private final CriticalWatchdogBootstrap watchdog;
    private final LivePreviewBootstrap livePreview;
    private final StageExecutorBootstrap stageExecutors;
    private final TriggerRuntimeBootstrap triggers;
    private final CameraInspectionLoopRunner inspectionLoops;

    public CameraRuntimeBootstrapImpl(Logger log) {
        super(log);
        this.maintenance = new RuntimeMaintenanceBootstrapImpl(log);
        this.fanOutHealth = new FanOutHealthBootstrapImpl(log);
        this.workers = new CameraWorkerBootstrapImpl(log);
        this.watchdog = new CriticalWatchdogBootstrapImpl(log);
        this.livePreview = new LivePreviewBootstrapImpl(log);
        this.stageExecutors = new StageExecutorBootstrapImpl(log);
        this.triggers = new TriggerRuntimeBootstrapImpl(log);
        this.inspectionLoops = new CameraInspectionLoopRunnerImpl(log);
    }

    @Override
    public boolean runBlocking(
            CameraRuntimeContext runtime,
            IntegrationServicePoolFactory poolFactory,
            IntegrationLifecycleComposite lifecycle
    ) throws Exception {
        CameraRuntimePorts ports = CameraRuntimePorts.of(runtime);

        maintenance.start(ports.maintenance());
        fanOutHealth.wire(ports.fanOutHealth());

        if (!workers.startWorkers(ports.cameraWorkers())) {
            return false;
        }
        workers.attachStreamService(ports.cameraWorkers());
        watchdog.start(ports.criticalWatchdog());

        livePreview.start(ports.livePreview());
        stageExecutors.create(ports.stageExecutors(), poolFactory);

        TriggerRuntimeBootstrap.TriggerWireResult triggerWire = triggers.wire(ports.triggerWiring());
        if (runtime.preview().livePreview() != null && runtime.triggers().lineCaptureCoordinator() != null) {
            runtime.preview().livePreview().setLineCaptureCoordinator(runtime.triggers().lineCaptureCoordinator());
        }

        lifecycle.registerAll(runtime.managedRuntimeComponents());
        lifecycle.start();

        inspectionLoops.runBlocking(ports.inspectionLoops(), triggerWire, runtime.health().stopSignal());
        return true;
    }
}
