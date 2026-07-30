package com.example.iml.orchestrator.integration.bootstrap.config;

/**
 * Параметры синхронной съёмки линии ({@code integration.simultaneous_line_capture}).
 * Сборка из YAML — {@link SimultaneousLineCaptureConfigMapper}.
 */
public record SimultaneousLineCaptureConfig(
        boolean enabled,
        long barrierWaitMs,
        long postTriggerSettleMs,
        long interWaitFrameMs,
        boolean parallelWaitFrame,
        boolean immediatePrefire,
        boolean hardwareLineTrigger,
        int transferWaitWaves,
        long transferWaveGapMs
) {
}
