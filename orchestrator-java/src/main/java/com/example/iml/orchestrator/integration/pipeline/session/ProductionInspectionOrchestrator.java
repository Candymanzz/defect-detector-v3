package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;

/**
 * Обычный режим: один цикл или continuous с задержкой между кадрами.
 */
public final class ProductionInspectionOrchestrator {

    private ProductionInspectionOrchestrator() {
    }

    public static void run(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection
    ) throws Exception {
        boolean runContinuous = continuousInspection.enabled();
        do {
            AsyncInspectionCycleRunner.run(svc, in, null);
            if (runContinuous && continuousInspection.cycleDelayMs() > 0) {
                try {
                    Thread.sleep(continuousInspection.cycleDelayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } while (runContinuous && !Thread.currentThread().isInterrupted());
    }
}
