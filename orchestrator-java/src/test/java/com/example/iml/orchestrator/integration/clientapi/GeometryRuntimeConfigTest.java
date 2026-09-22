package com.example.iml.orchestrator.integration.clientapi;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeometryRuntimeConfigTest {

    @Test
    void effectiveForDisplayMergesYamlProfileBeforeRuntimeOverrides() {
        Map<String, Object> yamlGeometry = Map.of(
                "max_joint_parallelism_deg", 2.5,
                "max_shift_mm", 0.5,
                "profiles", Map.of(
                        "bench-lan1", Map.of(
                                "max_joint_parallelism_deg", 5.0,
                                "max_shift_mm", 0.8
                        )
                )
        );
        GeometryRuntimeConfig runtime = new GeometryRuntimeConfig();
        runtime.replaceAllFromClient("bench-lan1", Map.of("maxShiftMm", 1.1));

        Map<String, Object> effective = runtime.effectiveForDisplay(
                yamlGeometry,
                Map.of("fallback_threshold", 0.25),
                "bench-lan1"
        );

        assertEquals(5.0, ((Number) effective.get("maxJointParallelismDeg")).doubleValue(), 1e-9);
        assertEquals(1.1, ((Number) effective.get("maxShiftMm")).doubleValue(), 1e-9);
    }

    @Test
    void effectiveForDisplayUsesYamlProfileWhenRuntimeEmpty() {
        Map<String, Object> yamlGeometry = Map.of(
                "max_joint_parallelism_deg", 2.5,
                "profiles", Map.of(
                        "bench-lan3", Map.of("max_joint_parallelism_deg", 5.0)
                )
        );
        GeometryRuntimeConfig runtime = new GeometryRuntimeConfig();

        Map<String, Object> effective = runtime.effectiveForDisplay(
                yamlGeometry,
                Map.of(),
                "bench-lan3"
        );

        assertEquals(5.0, ((Number) effective.get("maxJointParallelismDeg")).doubleValue(), 1e-9);
    }
}
