package com.example.iml.orchestrator.integration.trigger.strategy;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategy;

/** Непрерывный цикл инспекции без ожидания внешнего сигнала. */
public final class ContinuousTriggerStrategy implements InspectionTriggerStrategy {

    private final int cycleDelayMs;

    public ContinuousTriggerStrategy(int cycleDelayMs) {
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
