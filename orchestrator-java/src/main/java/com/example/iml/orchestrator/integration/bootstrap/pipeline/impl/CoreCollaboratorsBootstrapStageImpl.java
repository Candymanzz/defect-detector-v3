package com.example.iml.orchestrator.integration.bootstrap.pipeline.impl;

import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.AbstractBootstrapStage;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.BootstrapStageResult;

import com.example.iml.orchestrator.integration.bootstrap.context.ChildProcessesContext;
import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.service.api.CoreCollaboratorsBootstrap;
import org.apache.logging.log4j.Logger;

/**
 * Stage: gate / capture / client-api collaborators (до OS-процессов).
 */
public final class CoreCollaboratorsBootstrapStageImpl
        extends AbstractBootstrapStage<IntegrationRuntimeContext, IntegrationRuntimeContext> {

    private final CoreCollaboratorsBootstrap collaborators;

    public CoreCollaboratorsBootstrapStageImpl(Logger log, CoreCollaboratorsBootstrap collaborators) {
        super(log, "core-collaborators");
        this.collaborators = collaborators;
    }

    @Override
    protected BootstrapStageResult<IntegrationRuntimeContext> execute(IntegrationRuntimeContext session) {
        ChildProcessesContext processes = session.beginChildProcesses();
        collaborators.assemble(processes);
        return BootstrapStageResult.proceed(session);
    }
}
