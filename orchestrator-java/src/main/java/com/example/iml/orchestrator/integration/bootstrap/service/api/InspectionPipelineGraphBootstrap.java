package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.PipelineAssemblyContext;

/**
 * Сборка графа {@code InspectionPipeline} + reference registry / detector map.
 */
public interface InspectionPipelineGraphBootstrap {

    void assembleGraph(PipelineAssemblyContext assembly);
}
