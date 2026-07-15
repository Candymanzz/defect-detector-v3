package com.example.iml.orchestrator.integration.camera;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraSettingsServiceTest {

    @Test
    void parsePatchBodyKeepsSupportedFieldsOnly() {
        Map<String, Object> patch = CameraSettingsService.parsePatchBody(Map.of(
                "exposure_us", 4500,
                "gain_db", 2.5,
                "gamma", 1.1,
                "black_level", 8,
                "capture_trigger_mode", "software",
                "frame_timeout_ms", 12000,
                "unsupported", "ignored"
        ));

        assertEquals(6, patch.size());
        assertEquals(4500, patch.get("exposure_us"));
        assertEquals(2.5, patch.get("gain_db"));
        assertEquals(1.1, patch.get("gamma"));
        assertEquals(8, patch.get("black_level"));
        assertEquals("software", patch.get("capture_trigger_mode"));
        assertEquals(12000, patch.get("frame_timeout_ms"));
    }

    @Test
    void parsePatchBodyReturnsEmptyForBlankInput() {
        assertTrue(CameraSettingsService.parsePatchBody(Map.of()).isEmpty());
        assertTrue(CameraSettingsService.parsePatchBody(null).isEmpty());
    }

    @Test
    void filterSettingsRemovesExcludedKeys() {
        Map<String, Object> source = Map.of(
                "exposure_us", 4500,
                "capture_trigger_mode", "software"
        );
        Map<String, Object> filtered = CameraSettingsService.filterSettings(
                source,
                Set.of("capture_trigger_mode")
        );
        assertEquals(1, filtered.size());
        assertEquals(4500, filtered.get("exposure_us"));
    }
}
