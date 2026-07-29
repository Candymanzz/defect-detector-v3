package com.example.iml.orchestrator.integration.bootstrap.pipeline.api;

/**
 * Один последовательный шаг bootstrap: принимает типизированный вход, отдаёт типизированный выход.
 */
public interface BootstrapStage<I, O> {

    String name();

    BootstrapStageResult<O> run(I input) throws Exception;
}
