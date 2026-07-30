package com.example.iml.orchestrator.integration.bootstrap.pipeline.impl;

import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.AbstractBootstrapStage;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.BootstrapStageResult;

import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.PipelineAssemblyContext;
import com.example.iml.orchestrator.integration.bootstrap.service.api.InspectionPipelineGraphBootstrap;
import org.apache.logging.log4j.Logger;

/**
 * Stage: граф InspectionPipeline.
 */
public final class InspectionPipelineGraphBootstrapStageImpl
        extends AbstractBootstrapStage<IntegrationRuntimeContext, IntegrationRuntimeContext> {

    private final InspectionPipelineGraphBootstrap graph;

    public InspectionPipelineGraphBootstrapStageImpl(Logger log, InspectionPipelineGraphBootstrap graph) {
        super(log, "inspection-pipeline-graph");
        this.graph = graph;
    }

    @Override
    protected BootstrapStageResult<IntegrationRuntimeContext> execute(IntegrationRuntimeContext session) {
        PipelineAssemblyContext assembly = session.beginPipelineAssembly();
        graph.assembleGraph(assembly);
        return BootstrapStageResult.proceed(session);
    }
}
