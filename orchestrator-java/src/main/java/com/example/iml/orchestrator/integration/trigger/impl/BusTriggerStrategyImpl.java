package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;

/** Ожидание внешнего триггера через {@link InspectionTriggerBus} (UDP и др.). */
public final class BusTriggerStrategyImpl implements InspectionTriggerStrategy {

    private final InspectionTriggerBus bus;

    public BusTriggerStrategyImpl(InspectionTriggerBus bus) {
        this.bus = bus;
    }

    @Override
    public InspectionTriggerEvent awaitNext(int cameraId) throws InterruptedException {
        return bus.take(cameraId);
    }
}
