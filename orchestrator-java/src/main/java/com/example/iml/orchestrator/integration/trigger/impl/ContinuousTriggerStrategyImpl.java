package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;

/** Непрерывный цикл инспекции без ожидания внешнего сигнала. */
public final class ContinuousTriggerStrategyImpl implements InspectionTriggerStrategy {

    private final int cycleDelayMs;

    public ContinuousTriggerStrategyImpl(int cycleDelayMs) {
        this.cycleDelayMs = Math.max(0, cycleDelayMs);
    }

    @Override
    public InspectionTriggerEvent awaitNext(int cameraId) {
        return InspectionTriggerEvent.of(cameraId, "continuous");
    }

    @Override
    public int postCycleDelayMs() {
        return cycleDelayMs;
    }
}
