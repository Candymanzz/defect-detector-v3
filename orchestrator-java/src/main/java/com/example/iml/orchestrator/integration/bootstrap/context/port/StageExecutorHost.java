package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Порт stage executor pools.
 */
public interface StageExecutorHost {

    IntegrationBootConfig bootConfig();

    List<?> geometryPool();

    void setCameraExecutor(ExecutorService executor);

    void setCaptureStageExecutor(ExecutorService executor);

    void setPythonStageExecutor(ExecutorService executor);

    void setGeometryStageExecutor(ExecutorService executor);

    void setDecisionStageExecutor(ExecutorService executor);
}
