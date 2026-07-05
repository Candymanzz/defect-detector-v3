package com.example.iml.geometry.shm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReferenceShmMatCacheTest {

    @Test
    void referenceKeyIncludesCameraIdForPerCameraCache() {
        Map<String, Object> camera0 = Map.of(
                "camera_id", 0,
                "reference_shm_name", "ref_shm",
                "reference_shm_offset", 0,
                "reference_width", 2448,
                "reference_height", 2048,
                "reference_stride", 7344
        );
        Map<String, Object> camera5 = Map.of(
                "camera_id", 5,
                "reference_shm_name", "ref_shm",
                "reference_shm_offset", 0,
                "reference_width", 2448,
                "reference_height", 2048,
                "reference_stride", 7344
        );

        assertNotEquals(
                ReferenceShmMatCache.referenceKey(camera0),
                ReferenceShmMatCache.referenceKey(camera5)
        );
    }
}
