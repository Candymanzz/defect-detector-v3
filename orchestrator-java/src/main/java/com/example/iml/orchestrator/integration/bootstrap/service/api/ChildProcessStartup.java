package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.ChildProcessesContext;
import com.example.iml.orchestrator.integration.bootstrap.factory.IntegrationServicePoolFactory;

/**
 * Запуск дочерних процессов и пулов RPC/HTTP; сборка ранних collaborator'ов.
 */
public interface ChildProcessStartup {

    /**
     * @return {@code false} при пустом python pool (early-exit)
     */
    boolean start(ChildProcessesContext processes, IntegrationServicePoolFactory poolFactory);
}
