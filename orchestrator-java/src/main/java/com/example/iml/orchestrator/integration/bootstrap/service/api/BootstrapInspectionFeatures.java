package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionConfig;

import java.util.Collection;
import java.util.Map;

/**
 * Повторяющиеся разборы YAML-фич для camera-runtime bootstrap.
 */
public final class BootstrapInspectionFeatures {

    private BootstrapInspectionFeatures() {
    }

    public static IntegrationFeatureConfig.SaveCapturesConfig saveCaptures(Map<String, Object> integration) {
        return IntegrationFeatureConfig.parseSaveCaptures(integration);
    }

    public static IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub(
            Map<String, Object> integration
    ) {
        return IntegrationFeatureConfig.parseDevAutoTriggerStub(integration);
    }

    public static IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection(
            Map<String, Object> integration
    ) {
        return IntegrationFeatureConfig.parseContinuousInspection(integration);
    }

    public static BucketInspectionConfig bucketInspection(
            Map<String, Object> integration,
            Collection<Integer> cameraIds
    ) {
        return BucketInspectionConfig.parse(integration, cameraIds);
    }
}
