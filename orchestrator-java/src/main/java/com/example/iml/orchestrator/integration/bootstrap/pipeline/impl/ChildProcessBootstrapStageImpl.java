package com.example.iml.orchestrator.integration.bootstrap.pipeline.impl;

import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.AbstractBootstrapStage;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.BootstrapStageResult;

import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.service.api.ChildProcessStartup;
import org.apache.logging.log4j.Logger;

/**
 * Stage: child OS-процессы и RPC pools.
 */
public final class ChildProcessBootstrapStageImpl
        extends AbstractBootstrapStage<IntegrationRuntimeContext, IntegrationRuntimeContext> {

    private final ChildProcessStartup childProcesses;

    public ChildProcessBootstrapStageImpl(Logger log, ChildProcessStartup childProcesses) {
        super(log, "child-processes");
        this.childProcesses = childProcesses;
    }

    @Override
    protected BootstrapStageResult<IntegrationRuntimeContext> execute(IntegrationRuntimeContext session) {
        if (!childProcesses.start(session.childProcessesContext(), session.environment().poolFactory())) {
            return BootstrapStageResult.stop();
        }
        return BootstrapStageResult.proceed(session);
    }
}
