package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraWorkerHost;

/**
 * Запуск camera-worker процессов и client stream service.
 */
public interface CameraWorkerBootstrap {

    /**
     * @return {@code false} если ни один worker не стартовал
     */
    boolean startWorkers(CameraWorkerHost session);

    void attachStreamService(CameraWorkerHost session);
}
