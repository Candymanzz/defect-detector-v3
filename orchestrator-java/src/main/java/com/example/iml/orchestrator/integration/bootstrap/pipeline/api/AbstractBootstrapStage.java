package com.example.iml.orchestrator.integration.bootstrap.pipeline.api;

import org.apache.logging.log4j.Logger;

/**
 * База stage: общий logger и имя шага.
 */
public abstract class AbstractBootstrapStage<I, O> implements BootstrapStage<I, O> {

    protected final Logger log;
    private final String name;

    protected AbstractBootstrapStage(Logger log, String name) {
        this.log = log;
        this.name = name;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final BootstrapStageResult<O> run(I input) throws Exception {
        log.info("bootstrap stage start name={}", name);
        BootstrapStageResult<O> result = execute(input);
        if (result == null || !result.continuePipeline()) {
            log.info("bootstrap stage stop name={}", name);
            return BootstrapStageResult.stop();
        }
        log.info("bootstrap stage done name={}", name);
        return result;
    }

    protected abstract BootstrapStageResult<O> execute(I input) throws Exception;
}
