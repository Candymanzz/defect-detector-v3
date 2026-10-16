package com.example.iml.orchestrator.integration.config;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InspectionScaleConfigTest {
    @Test
    void supportsYamlAndJsonCameraKeysWithoutAffectingOtherCameras() {
        for (var overrides : new Map<?, ?>[] {
                Map.of(2, 0.5, 7, 0.5), Map.of("2", 0.5, "7", 0.5) }) {
            Map<String, Object> config = Map.of("inspect_scale", 0.75, "inspect_scale_by_camera", overrides);
            for (int camera = 0; camera < 10; camera++) {
                assertEquals(camera == 2 || camera == 7 ? 0.5 : 0.75,
                        InspectionScaleConfig.resolve(config, camera));
            }
        }
    }

    @Test
    void invalidOverridesFallBackAndCameraCanDisableGlobalDownscale() {
        for (Object invalid : new Object[] { 0, -0.5, 2, Double.NaN, Double.POSITIVE_INFINITY, "bad" }) {
            assertEquals(0.75, InspectionScaleConfig.resolve(
                    Map.of("inspect_scale", 0.75, "inspect_scale_by_camera", Map.of(2, invalid)), 2));
        }
        assertEquals(1.0, InspectionScaleConfig.resolve(
                Map.of("inspect_scale", 0.75, "inspect_scale_by_camera", Map.of(2, 1.0)), 2));
        assertEquals(1.0, InspectionScaleConfig.resolve(null, 2));
        assertEquals(1.0, InspectionScaleConfig.resolve(Map.of(), 2));
    }
}
