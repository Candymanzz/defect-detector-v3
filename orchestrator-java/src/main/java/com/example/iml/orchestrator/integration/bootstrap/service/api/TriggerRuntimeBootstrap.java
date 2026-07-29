package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.port.TriggerWiringHost;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wiring line-sync / trigger runtime / interval flash / bucket aggregator.
 */
public interface TriggerRuntimeBootstrap {

    TriggerWireResult wire(TriggerWiringHost session);

    record TriggerWireResult(
            IntegrationFeatureConfig.InspectionTriggerMode triggerMode,
            List<Integer> inspectionCameraIds,
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection,
            IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub,
            AtomicBoolean softwareVisionReady,
            Runnable refreshVisionReady
    ) {
    }
}
