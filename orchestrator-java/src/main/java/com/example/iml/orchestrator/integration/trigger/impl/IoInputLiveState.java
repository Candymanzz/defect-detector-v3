package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;

/**
 * Shared mutable DI / capture pulse state for {@link IoInputMonitorUdpTriggerTransportImpl}
 * and {@link IoInputLineCapturePublisher}.
 */
final class IoInputLiveState {

    volatile boolean workActive;
    volatile boolean directionRawActive;
    volatile boolean directionActive;
    /** Первый DI2=1 при direction_latch — дальше DI2 холостой для съёмки. */
    volatile boolean directionLatched;
    volatile boolean directionInitialized;
    volatile boolean triggerActive;
    volatile boolean captureFiredThisPulse;
    /** Один кадр на окно DI2=1: повторный DI3↑ при DI2=1 — холостой. */
    volatile boolean captureFiredThisDi2Window;
    long lastFireMs;

    boolean isEffectiveWork(IoInputDiscreteConfig ioInputConfig) {
        return ioInputConfig.stubWorkActive() || workActive;
    }
}
