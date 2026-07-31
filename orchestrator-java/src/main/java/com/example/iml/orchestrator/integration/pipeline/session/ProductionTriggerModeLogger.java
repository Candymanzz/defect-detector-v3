package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;

/** Startup log lines for the production trigger mode. */
final class ProductionTriggerModeLogger {

    private ProductionTriggerModeLogger() {
    }

    static void log(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            IntegrationFeatureConfig.InspectionTriggerMode mode,
            InspectionTriggerStrategy strategy,
            boolean referenceFromClient,
            boolean captureWithoutReference
    ) {
        int cameraId = in.cameraId();
        switch (mode) {
            case TIMER -> {
                if (referenceFromClient && !captureWithoutReference) {
                    svc.log().info(
                            "integration cam={}: timer trigger — inspection only after client.reference_bundle",
                            cameraId
                    );
                } else if (referenceFromClient) {
                    svc.log().info(
                            "integration cam={}: timer trigger — capture without reference enabled",
                            cameraId
                    );
                } else {
                    svc.log().warn(
                            "integration cam={}: dev_auto_trigger_stub (timer) — temporary stub instead of external trigger",
                            cameraId
                    );
                }
            }
            case CONTINUOUS -> svc.log().info("integration cam={}: continuous_inspection enabled", cameraId);
            case EXTERNAL -> {
                if (referenceFromClient && !captureWithoutReference) {
                    svc.log().info(
                            "integration cam={}: waiting for external trigger (e.g. UDP) after client.reference_bundle",
                            cameraId
                    );
                } else if (referenceFromClient) {
                    svc.log().info(
                            "integration cam={}: waiting for external trigger — capture without reference, full inspection after reference_bundle",
                            cameraId
                    );
                } else {
                    svc.log().info("integration cam={}: waiting for external trigger (e.g. UDP)", cameraId);
                }
            }
            default -> { }
        }
        if (strategy.postCycleDelayMs() > 0 && mode == IntegrationFeatureConfig.InspectionTriggerMode.CONTINUOUS) {
            svc.log().debug("integration cam={}: post_cycle_delay_ms={}", cameraId, strategy.postCycleDelayMs());
        }
    }
}
