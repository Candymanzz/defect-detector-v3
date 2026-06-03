package com.example.iml.orchestrator.integration.trigger.strategy;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategy;

/** Периодический триггер (бывший dev_auto_trigger_stub). */
public final class TimerTriggerStrategy implements InspectionTriggerStrategy {

    private final int intervalMs;

    public TimerTriggerStrategy(int intervalMs) {
        this.intervalMs = Math.max(1000, intervalMs);
    }

    @Override
    public InspectionTriggerEvent awaitNext(int cameraId) throws InterruptedException {
        Thread.sleep(intervalMs);
        return InspectionTriggerEvent.of(cameraId, "timer");
    }
}
