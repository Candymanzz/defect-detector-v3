package com.example.iml.orchestrator.integration.bootstrap.service.trigger;

import com.example.iml.orchestrator.integration.bootstrap.service.api.BootstrapInspectionFeatures;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Objects;

/**
 * Resolve inspection trigger mode and log IO-input policy from YAML.
 */
public final class TriggerModeResolver {

    private final Logger log;

    public TriggerModeResolver(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    public record ResolvedTriggerMode(
            InspectionTriggerConfig inspectionTriggerConfig,
            IntegrationFeatureConfig.InspectionTriggerMode triggerMode,
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection
    ) {
    }

    public ResolvedTriggerMode resolve(Map<String, Object> integration) {
        IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection =
                BootstrapInspectionFeatures.continuousInspection(integration);
        InspectionTriggerConfig inspectionTriggerConfig = InspectionTriggerConfig.parse(integration);
        IntegrationFeatureConfig.InspectionTriggerMode triggerMode =
                inspectionTriggerConfig.ioInput().di3Only()
                        || inspectionTriggerConfig.ioInput().directionLatchOnWork()
                        ? IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL
                        : IntegrationFeatureConfig.resolveInspectionTriggerMode(integration);
        logIoPolicy(integration, inspectionTriggerConfig, continuousInspection);
        return new ResolvedTriggerMode(inspectionTriggerConfig, triggerMode, continuousInspection);
    }

    private void logIoPolicy(
            Map<String, Object> integration,
            InspectionTriggerConfig inspectionTriggerConfig,
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection
    ) {
        if (inspectionTriggerConfig.ioInput().di3Only()) {
            log.info(
                    "inspection_trigger di3_only=true — съёмка по фронту DI{}, направление по текущему DI{}",
                    inspectionTriggerConfig.ioInput().triggerPort(),
                    inspectionTriggerConfig.ioInput().directionPort()
            );
            if (BootstrapInspectionFeatures.devAutoTriggerStub(integration).enabled()) {
                log.warn("di3_only=true: dev_auto_trigger_stub включён в конфиге, но игнорируется");
            }
            if (continuousInspection.enabled()) {
                log.warn("di3_only=true: continuous_inspection включён в конфиге, но игнорируется");
            }
        }
        if (inspectionTriggerConfig.ioInput().di3Only()
                && inspectionTriggerConfig.ioInput().requireDirection()) {
            log.info(
                    "inspection_trigger DI2→DI3: съёмка по DI{}↑ только при DI{}=1",
                    inspectionTriggerConfig.ioInput().triggerPort(),
                    inspectionTriggerConfig.ioInput().directionPort()
            );
        }
        if (inspectionTriggerConfig.ioInput().directionLatchOnWork()) {
            log.info(
                    "inspection_trigger direction_latch_on_work=true — DI2 фиксируется при DI1↑, съёмка только по DI{}",
                    inspectionTriggerConfig.ioInput().triggerPort()
            );
        }
    }
}
