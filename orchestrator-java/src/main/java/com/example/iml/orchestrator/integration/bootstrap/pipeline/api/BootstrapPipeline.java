package com.example.iml.orchestrator.integration.bootstrap.pipeline.api;

import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.function.Function;

/**
 * Линейный bootstrap-pipeline: каждый stage потребляет выход предыдущего.
 */
public final class BootstrapPipeline<T> {

    private final Logger log;
    private final T current;
    private final boolean active;

    private BootstrapPipeline(Logger log, T current, boolean active) {
        this.log = log;
        this.current = current;
        this.active = active;
    }

    public static <T> BootstrapPipeline<T> start(Logger log, T seed) {
        return new BootstrapPipeline<>(Objects.requireNonNull(log, "log"), seed, true);
    }

    public <O> BootstrapPipeline<O> then(BootstrapStage<? super T, O> stage) throws Exception {
        Objects.requireNonNull(stage, "stage");
        if (!active) {
            return new BootstrapPipeline<>(log, null, false);
        }
        BootstrapStageResult<O> result = stage.run(current);
        if (result == null || !result.continuePipeline()) {
            return new BootstrapPipeline<>(log, null, false);
        }
        return new BootstrapPipeline<>(log, result.value(), true);
    }

    public <O> BootstrapPipeline<O> map(Function<? super T, ? extends O> mapper) {
        if (!active) {
            return new BootstrapPipeline<>(log, null, false);
        }
        return new BootstrapPipeline<>(log, mapper.apply(current), true);
    }

    public boolean isActive() {
        return active;
    }

    public T orElseNull() {
        return active ? current : null;
    }

    public T requireValue() {
        if (!active || current == null) {
            throw new IllegalStateException("bootstrap pipeline stopped or empty");
        }
        return current;
    }
}
