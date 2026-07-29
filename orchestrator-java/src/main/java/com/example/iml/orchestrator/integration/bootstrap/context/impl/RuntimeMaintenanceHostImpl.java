package com.example.iml.orchestrator.integration.bootstrap.context.impl;

import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.port.AbstractCameraRuntimeHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.RuntimeMaintenanceHost;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/** Адаптер: SHM janitor / timing log. */
public final class RuntimeMaintenanceHostImpl extends AbstractCameraRuntimeHost implements RuntimeMaintenanceHost {

    public RuntimeMaintenanceHostImpl(CameraRuntimeContext runtime) {
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
    public void setShmJanitorScheduler(ScheduledExecutorService scheduler) {
        stages().setShmJanitorScheduler(scheduler);
    }

    @Override
    public void setPipelineStagesLog(PipelineStagesLog pipelineStagesLog) {
        stages().setPipelineStagesLog(pipelineStagesLog);
    }
}
