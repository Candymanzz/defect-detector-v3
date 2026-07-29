package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;

/** Периодический триггер (бывший dev_auto_trigger_stub). */
public final class TimerTriggerStrategyImpl implements InspectionTriggerStrategy {

    private final int intervalMs;

    public TimerTriggerStrategyImpl(int intervalMs) {
        this.intervalMs = Math.max(1000, intervalMs);
    }

    @Override
    public InspectionTriggerEvent awaitNext(int cameraId) throws InterruptedException {
        Thread.sleep(intervalMs);
        return InspectionTriggerEvent.of(cameraId, "timer");
    }
}
