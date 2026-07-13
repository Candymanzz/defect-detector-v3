package com.example.iml.orchestrator.integration.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationFeatureConfigTest {

    @Test
    void resolvesExternalModeByDefault() {
        assertEquals(
                IntegrationFeatureConfig.InspectionTriggerMode.EXTERNAL,
                IntegrationFeatureConfig.resolveInspectionTriggerMode(Map.of())
        );
    }

    @Test
    void devStubTakesPriorityOverContinuous() {
        Map<String, Object> integration = Map.of(
                "dev_auto_trigger_stub", Map.of("enabled", true, "interval_ms", 2000),
                "continuous_inspection", Map.of("enabled", true, "cycle_delay_ms", 100)
        );

        assertEquals(
                IntegrationFeatureConfig.InspectionTriggerMode.TIMER,
                IntegrationFeatureConfig.resolveInspectionTriggerMode(integration)
        );
    }

    @Test
    void continuousModeWhenEnabled() {
        Map<String, Object> integration = Map.of(
                "continuous_inspection", Map.of("enabled", true, "cycle_delay_ms", 250)
        );

        assertEquals(
                IntegrationFeatureConfig.InspectionTriggerMode.CONTINUOUS,
                IntegrationFeatureConfig.resolveInspectionTriggerMode(integration)
        );
        assertEquals(250, IntegrationFeatureConfig.parseContinuousInspection(integration).cycleDelayMs());
    }

    @Test
    void parseSaveCapturesClampsQuality() {
        Map<String, Object> integration = Map.of(
                "save_captures", Map.of("enabled", false, "directory", "captures", "jpeg_quality", 2.0)
        );

        IntegrationFeatureConfig.SaveCapturesConfig cfg = IntegrationFeatureConfig.parseSaveCaptures(integration);

        assertFalse(cfg.enabled());
        assertEquals("captures", cfg.relativeDir());
        assertEquals(1.0f, cfg.jpegQuality(), 0.001f);
    }

    @Test
    void parseCaptureFrameDownscaleDisabledWhenScaleIsOne() {
        Map<String, Object> integration = Map.of(
                "capture_frame_downscale", Map.of("enabled", true, "scale", 1.0)
        );

        IntegrationFeatureConfig.CaptureFrameDownscaleConfig cfg =
                IntegrationFeatureConfig.parseCaptureFrameDownscale(integration);

        assertFalse(cfg.enabled());
    }

    @Test
    void captureWithoutReferenceDefaultsToClientReferenceSource() {
        Map<String, Object> integration = Map.of("reference_source", "client");

        assertTrue(IntegrationFeatureConfig.parseCaptureWithoutReference(integration));
    }

    @Test
    void inspectionCycleTimeoutHasMinimum() {
        assertEquals(4000, IntegrationFeatureConfig.parseInspectionCycleTimeoutMs(null));
        assertEquals(500, IntegrationFeatureConfig.parseInspectionCycleTimeoutMs(Map.of("inspection_cycle_timeout_ms", 10)));
    }
}
