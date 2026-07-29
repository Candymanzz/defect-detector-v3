package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.port.StageExecutorHost;
import com.example.iml.orchestrator.integration.bootstrap.factory.IntegrationServicePoolFactory;

/**
 * Создание stage executor'ов capture/python/geometry/decision.
 */
public interface StageExecutorBootstrap {

    void create(StageExecutorHost session, IntegrationServicePoolFactory poolFactory);
}
