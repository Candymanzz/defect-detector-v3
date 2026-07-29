package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.PipelineAssemblyContext;

/**
 * Engage освещения: brightness store, LightTriggerClient, startup COM bank.
 */
public interface LightingEngageBootstrap {

    void engage(PipelineAssemblyContext assembly);
}
