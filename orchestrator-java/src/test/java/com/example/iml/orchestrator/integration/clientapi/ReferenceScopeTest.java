package com.example.iml.orchestrator.integration.clientapi;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceScopeTest {
    @Test
    void sameProductAndCameraHaveSeparatePhaseKeys() {
        Map<String, Object> first = Map.of("product_type", "bucket", "camera_id", 3, "phase_id", 0);
        Map<String, Object> second = Map.of("product_type", "bucket", "camera_id", 3, "phase_id", 1);
        assertEquals("bucket#phase=0#cam=3", AnalisSurfaceHttpBinaryRpcSupervisor.bindReferenceScope(first).get("product_type"));
        assertEquals("bucket#phase=1#cam=3", AnalisSurfaceHttpBinaryRpcSupervisor.bindReferenceScope(second).get("product_type"));
        assertEquals("bucket", second.get("product_type"));
    }

    @Test
    void rejectsReferenceFromAnotherPhaseOrCamera() {
        assertThrows(IllegalArgumentException.class, () -> AnalisSurfaceHttpBinaryRpcSupervisor.bindReferenceScope(
                Map.of("product_type", "bucket#phase=0#cam=3", "camera_id", 3, "phase_id", 1)));
        for (String name : new String[]{"/iml_ref_phase0_cam3", "/iml_ref_phase1_cam4"}) {
            assertThrows(IllegalArgumentException.class, () -> AnalisSurfaceHttpBinaryRpcSupervisor.bindReferenceScope(
                    Map.of("product_type", "bucket", "camera_id", 3, "phase_id", 1, "reference_shm_name", name)));
        }
    }

    @Test
    void acceptsMatchingReference() {
        assertEquals("bucket#phase=1#cam=3", AnalisSurfaceHttpBinaryRpcSupervisor.bindReferenceScope(
                Map.of("product_type", "bucket#phase=1#cam=3", "camera_id", 3, "phase_id", 1,
                        "reference_shm_name", "/iml_ref_phase1_cam3")).get("product_type"));
    }
}
