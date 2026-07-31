package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.port.RuntimeMaintenanceHost;

/**
 * Запуск SHM janitor и optional timing_stages_log.
 */
public interface RuntimeMaintenanceBootstrap {

    void start(RuntimeMaintenanceHost session);
}
