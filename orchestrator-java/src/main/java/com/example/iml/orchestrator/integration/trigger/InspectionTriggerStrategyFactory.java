package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.trigger.strategy.BusTriggerStrategy;
import com.example.iml.orchestrator.integration.trigger.strategy.ContinuousTriggerStrategy;
import com.example.iml.orchestrator.integration.trigger.strategy.TimerTriggerStrategy;

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
            case TIMER -> new TimerTriggerStrategy(devStub.intervalMs());
            case CONTINUOUS -> new ContinuousTriggerStrategy(continuous.cycleDelayMs());
            case EXTERNAL -> new BusTriggerStrategy(bus);
        };
    }
}
