package com.example.iml.orchestrator.integration.bootstrap.context.impl;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.port.AbstractCameraRuntimeHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.StageExecutorHost;

import java.util.List;
import java.util.concurrent.ExecutorService;

/** Адаптер: stage executor pools. */
public final class StageExecutorHostImpl extends AbstractCameraRuntimeHost implements StageExecutorHost {

    public StageExecutorHostImpl(CameraRuntimeContext runtime) {
        super(runtime);
    }

    @Override
    public IntegrationBootConfig bootConfig() {
        return bootCfg();
    }

    @Override
    public List<?> geometryPool() {
        return processes().geometryPool();
    }

    @Override
    public void setCameraExecutor(ExecutorService executor) {
        stages().setCameraExecutor(executor);
    }

    @Override
    public void setCaptureStageExecutor(ExecutorService executor) {
        stages().setCaptureStageExecutor(executor);
    }

    @Override
    public void setPythonStageExecutor(ExecutorService executor) {
        stages().setPythonStageExecutor(executor);
    }

    @Override
    public void setGeometryStageExecutor(ExecutorService executor) {
        stages().setGeometryStageExecutor(executor);
    }

    @Override
    public void setDecisionStageExecutor(ExecutorService executor) {
        stages().setDecisionStageExecutor(executor);
    }
}
