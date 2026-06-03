package com.example.iml.orchestrator.integration.trigger;

/**
 * Delayed binding of {@link InspectionTriggerBus} to the HTTP layer.
 */
public final class InspectionTriggerBusHolder {

    private volatile InspectionTriggerBus bus;

    public InspectionTriggerBus get() {
        return bus;
    }

    public void set(InspectionTriggerBus bus) {
        this.bus = bus;
    }
}
