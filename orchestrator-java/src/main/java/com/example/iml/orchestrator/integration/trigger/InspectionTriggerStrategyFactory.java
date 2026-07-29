package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.trigger.impl.BusTriggerStrategyImpl;
import com.example.iml.orchestrator.integration.trigger.impl.ContinuousTriggerStrategyImpl;
import com.example.iml.orchestrator.integration.trigger.impl.TimerTriggerStrategyImpl;

public final class InspectionTriggerStrategyFactory {

    private InspectionTriggerStrategyFactory() {
    }

    public static InspectionTriggerStrategy create(
            IntegrationFeatureConfig.InspectionTriggerMode mode,
            InspectionTriggerBus bus,
            IntegrationFeatureConfig.DevAutoTriggerStubConfig devStub,
            IntegrationFeatureConfig.ContinuousInspectionConfig continuous
    ) {
        return switch (mode) {
            case TIMER -> new TimerTriggerStrategyImpl(devStub.intervalMs());
            case CONTINUOUS -> new ContinuousTriggerStrategyImpl(continuous.cycleDelayMs());
            case EXTERNAL -> new BusTriggerStrategyImpl(bus);
        };
    }
}
