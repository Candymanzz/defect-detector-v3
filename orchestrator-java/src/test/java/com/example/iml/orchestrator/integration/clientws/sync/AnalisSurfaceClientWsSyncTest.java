package com.example.iml.orchestrator.integration.clientws.sync;

import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class AnalisSurfaceClientWsSyncTest {

    @Test
    void includesCameraIdInFpZoneReplacement() {
        Map<String, Object> command = AnalisSurfaceClientWsSync.replaceFpZones(
                "profile-a",
                2,
                320,
                240,
                List.of()
        );

        assertEquals("replace_fp_zones", command.get("op"));
        assertEquals("profile-a", command.get("product_type"));
        assertEquals(2, command.get("camera_id"));
    }

    @Test
    void emptyBundleZonesDoNotClearPersistedDetectorZones() {
        ReferenceBundleSnapshot snapshot = new ReferenceBundleSnapshot(
                "profile-a",
                List.of(),
                0,
                320,
                240,
                List.of(),
                1L
        );

        Map<String, Object> command = AnalisSurfaceClientWsSync.syncClientReferenceBundle(snapshot, 0);

        assertFalse(command.containsKey("fp_zones"));
    }
}
