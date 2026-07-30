package com.example.iml.orchestrator.integration.bootstrap.pipeline.impl;

import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.AbstractBootstrapStage;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.BootstrapStageResult;

import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.service.api.LightingEngageBootstrap;
import org.apache.logging.log4j.Logger;

/**
 * Stage: engage освещения.
 */
public final class LightingEngageBootstrapStageImpl
        extends AbstractBootstrapStage<IntegrationRuntimeContext, IntegrationRuntimeContext> {

    private final LightingEngageBootstrap lighting;

    public LightingEngageBootstrapStageImpl(Logger log, LightingEngageBootstrap lighting) {
        super(log, "lighting-engage");
        this.lighting = lighting;
    }

    @Override
    protected BootstrapStageResult<IntegrationRuntimeContext> execute(IntegrationRuntimeContext session) {
        lighting.engage(session.pipelineAssemblyContext());
        return BootstrapStageResult.proceed(session);
    }
}
