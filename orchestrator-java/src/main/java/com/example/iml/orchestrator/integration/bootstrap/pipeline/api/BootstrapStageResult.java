package com.example.iml.orchestrator.integration.bootstrap.pipeline.api;

/**
 * Результат шага bootstrap-pipeline: продолжить с {@code value} или остановить цепочку (early-exit).
 */
public record BootstrapStageResult<T>(boolean continuePipeline, T value) {

    public static <T> BootstrapStageResult<T> proceed(T value) {
        return new BootstrapStageResult<>(true, value);
    }

    public static <T> BootstrapStageResult<T> stop() {
        return new BootstrapStageResult<>(false, null);
    }
}
