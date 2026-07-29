package com.example.iml.orchestrator.integration.bootstrap.pipeline.impl;

import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.AbstractBootstrapStage;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.BootstrapStageResult;

import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.UiRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.service.api.UiRuntimeBootstrap;
import org.apache.logging.log4j.Logger;

/**
 * Stage: UI HTTP / client WS / archive.
 */
public final class UiRuntimeBootstrapStageImpl
        extends AbstractBootstrapStage<IntegrationRuntimeContext, IntegrationRuntimeContext> {

    private final UiRuntimeBootstrap ui;

    public UiRuntimeBootstrapStageImpl(Logger log, UiRuntimeBootstrap ui) {
        super(log, "ui-runtime");
        this.ui = ui;
    }

    @Override
    protected BootstrapStageResult<IntegrationRuntimeContext> execute(IntegrationRuntimeContext session) {
        UiRuntimeContext ctx = session.beginUiRuntime();
        ui.bootstrap(ctx);
        return BootstrapStageResult.proceed(session);
    }
}
