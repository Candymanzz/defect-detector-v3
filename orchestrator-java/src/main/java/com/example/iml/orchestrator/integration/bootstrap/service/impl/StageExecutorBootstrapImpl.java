package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.StageExecutorBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.context.port.StageExecutorHost;
import com.example.iml.orchestrator.integration.bootstrap.factory.IntegrationServicePoolFactory;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;

/**
 * Только thread-pool'ы стадий inspection pipeline.
 */
public final class StageExecutorBootstrapImpl extends AbstractBootstrapService implements StageExecutorBootstrap {

    public StageExecutorBootstrapImpl(Logger log) {
        super(log);
    }

    @Override
    public void create(StageExecutorHost session, IntegrationServicePoolFactory poolFactory) {
        var cfg = session.bootConfig();
        session.setCameraExecutor(Executors.newFixedThreadPool(cfg.cameraParallelism(), r -> {
            Thread t = new Thread(r, "camera-flow");
            t.setDaemon(true);
            return t;
        }));
        session.setCaptureStageExecutor(poolFactory.createStageExecutor(
                "stage-capture", cfg.cameraParallelism(), cfg.stageQueueSize()));
        session.setPythonStageExecutor(poolFactory.createStageExecutor(
                "stage-python", cfg.pythonParallelism(), cfg.stageQueueSize()));
        session.setGeometryStageExecutor(poolFactory.createStageExecutor(
                "stage-geometry", Math.max(1, session.geometryPool().size()), cfg.stageQueueSize()));
        session.setDecisionStageExecutor(poolFactory.createStageExecutor(
                "stage-decision", cfg.cameraParallelism(), cfg.stageQueueSize()));
        log.info("pipeline settings: queue_size={} python_parallelism={}", cfg.stageQueueSize(), cfg.pythonParallelism());
    }
}
