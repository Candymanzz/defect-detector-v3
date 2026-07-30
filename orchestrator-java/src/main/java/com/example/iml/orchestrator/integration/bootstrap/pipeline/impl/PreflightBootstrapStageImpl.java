package com.example.iml.orchestrator.integration.bootstrap.pipeline.impl;

import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.AbstractBootstrapStage;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.BootstrapStageResult;

import com.example.iml.orchestrator.integration.bootstrap.context.BootstrapEnvironment;
import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.PreflightContext;
import com.example.iml.orchestrator.integration.bootstrap.service.api.PreflightBootstrap;
import org.apache.logging.log4j.Logger;

/**
 * Stage: BootstrapEnvironment → session + PreflightContext.
 */
public final class PreflightBootstrapStageImpl
        extends AbstractBootstrapStage<BootstrapEnvironment, IntegrationRuntimeContext> {

    private final PreflightBootstrap preflight;

    public PreflightBootstrapStageImpl(Logger log, PreflightBootstrap preflight) {
        super(log, "preflight");
        this.preflight = preflight;
    }

    @Override
    protected BootstrapStageResult<IntegrationRuntimeContext> execute(BootstrapEnvironment env) {
        IntegrationRuntimeContext session = new IntegrationRuntimeContext(env);
        PreflightContext ctx = session.beginPreflight();
        if (!preflight.run(ctx)) {
            return BootstrapStageResult.stop();
        }
        return BootstrapStageResult.proceed(session);
    }
}
