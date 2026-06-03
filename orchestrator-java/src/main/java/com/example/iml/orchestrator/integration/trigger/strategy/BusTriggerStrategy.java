package com.example.iml.orchestrator.integration.trigger.strategy;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategy;

/** Ожидание внешнего триггера через {@link InspectionTriggerBus} (UDP и др.). */
public final class BusTriggerStrategy implements InspectionTriggerStrategy {

    private final InspectionTriggerBus bus;

    public BusTriggerStrategy(InspectionTriggerBus bus) {
        this.bus = bus;
    }

    @Override
    public InspectionTriggerEvent awaitNext(int cameraId) throws InterruptedException {
        return bus.take(cameraId);
    }
}
