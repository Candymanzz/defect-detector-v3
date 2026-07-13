package com.example.iml.orchestrator.integration.ui;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometrySnapshotCacheTest {

    @Test
    void recordAndGetSnapshot() {
        GeometrySnapshotCache cache = new GeometrySnapshotCache();
        Map<String, Object> header = new HashMap<>(Map.of("overallPass", true, "status", "PASS"));

        cache.record(1, 100L, header);
        header.put("overallPass", false);

        Optional<GeometrySnapshotCache.Snapshot> snapshot = cache.get(1);

        assertTrue(snapshot.isPresent());
        assertEquals(100L, snapshot.get().frameId());
        assertEquals(true, snapshot.get().geometryHeader().get("overallPass"));
        assertTrue(cache.cameraIds().contains(1));
    }

    @Test
    void ignoresNullHeader() {
        GeometrySnapshotCache cache = new GeometrySnapshotCache();

        cache.record(2, 1L, null);

        assertFalse(cache.get(2).isPresent());
    }
}
