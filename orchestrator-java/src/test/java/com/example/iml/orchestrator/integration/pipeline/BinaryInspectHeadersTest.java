package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinaryInspectHeadersTest {

    private final BinaryProtocol.Message capture = new BinaryProtocol.Message(
            BinaryProtocol.MSG_RESPONSE,
            Map.of(
                    "frame_id", 1L,
                    "shm_name", "cam_shm",
                    "shm_offset", 0,
                    "width", 2448,
                    "height", 2048,
                    "stride", 7344
            ),
            new byte[0]
    );

    @Test
    void jointRoiOnAllBucketCamerasWithJointMode() {
        Map<String, Object> jointNorm = Map.of("x", 0.1, "y", 0.1, "width", 0.2, "height", 0.2);
        List<Map<String, Object>> interestPoly = List.of(
                Map.of("x", 0.1, "y", 0.1),
                Map.of("x", 0.9, "y", 0.1),
                Map.of("x", 0.9, "y", 0.9)
        );
        ReferenceSnapshot jointCameraRef = new ReferenceSnapshot("product", Map.of(
                "width", 2448,
                "height", 2048,
                "client_reference_bundle", true,
                "joint_camera_id", 2,
                "joint_roi_norm", jointNorm,
                "interest_polygon_norm", interestPoly,
                "shm_name", "ref_shm",
                "shm_offset", 0,
                "stride", 7344
        ));
        ReferenceSnapshot otherCameraRef = new ReferenceSnapshot("product", Map.of(
                "width", 2448,
                "height", 2048,
                "client_reference_bundle", true,
                "joint_camera_id", 2,
                "bucket_group_id", 0,
                "joint_roi_norm", jointNorm,
                "interest_polygon_norm", interestPoly,
                "shm_name", "ref_shm_cam1",
                "shm_offset", 0,
                "stride", 7344
        ));

        Map<String, Object> jointHeader = BinaryInspectHeaders.geometryInspectHeader(
                2, capture, jointCameraRef, null, null);
        Map<String, Object> otherHeader = BinaryInspectHeaders.geometryInspectHeader(
                1, capture, otherCameraRef, null, null);

        assertNotNull(jointHeader.get("jointRoi"));
        assertNotNull(otherHeader.get("jointRoi"));
        assertEquals("full", jointHeader.get("jointMode"));
        assertEquals("visibility", otherHeader.get("jointMode"));
        assertNotNull(otherHeader.get("wrinklesRoi"));
        assertEquals(otherHeader.get("mainRoi"), otherHeader.get("wrinklesRoi"));
        assertEquals(0.5, jointHeader.get("maxJointDefectMm"));
        assertEquals(0.25, jointHeader.get("jointMinWidthMm"));
        assertEquals(3.0, jointHeader.get("jointMaxWidthMm"));
        assertEquals(5.0, jointHeader.get("maxJointParallelismDeg"));
        assertEquals(0.8, jointHeader.get("maxJointTaperMm"));
        assertEquals(0.5, jointHeader.get("jointSeamSegmentationSensitivity"));
    }

    @Test
    void twoBucketsUseIndependentJointCameras() {
        Map<String, Object> jointNorm = Map.of("x", 0.1, "y", 0.1, "width", 0.2, "height", 0.2);
        ReferenceSnapshot bucket0Joint = new ReferenceSnapshot("product-a", Map.of(
                "width", 2448,
                "height", 2048,
                "client_reference_bundle", true,
                "bucket_group_id", 0,
                "joint_camera_id", 2,
                "joint_roi_norm", jointNorm,
                "shm_name", "ref_shm_2",
                "shm_offset", 0,
                "stride", 7344
        ));
        ReferenceSnapshot bucket1Joint = new ReferenceSnapshot("product-b", Map.of(
                "width", 2448,
                "height", 2048,
                "client_reference_bundle", true,
                "bucket_group_id", 1,
                "joint_camera_id", 7,
                "joint_roi_norm", jointNorm,
                "shm_name", "ref_shm_7",
                "shm_offset", 0,
                "stride", 7344
        ));

        Map<String, Object> bucket0Header = BinaryInspectHeaders.geometryInspectHeader(
                2, capture, bucket0Joint, null, null);
        Map<String, Object> bucket1Header = BinaryInspectHeaders.geometryInspectHeader(
                7, capture, bucket1Joint, null, null);

        assertNotNull(bucket0Header.get("jointRoi"));
        assertNotNull(bucket1Header.get("jointRoi"));
    }

    @Test
    void binaryHeadersCarryReferencePhaseAndGroup() {
        ReferenceSnapshot reference = new ReferenceSnapshot("product#phase=1#cam=2", Map.of(
                "width", 2448, "height", 2048, "stride", 7344,
                "shm_name", "ref_phase1", "shm_offset", 0,
                "phase_id", 1, "group_id", 2
        ));

        Map<String, Object> geometry = BinaryInspectHeaders.geometryInspectHeader(
                2, capture, reference, null, null);
        Map<String, Object> python = BinaryInspectHeaders.pythonInspectHeader(
                2, reference.productType(), "v1", capture, null, null, false, reference);

        assertEquals(1, geometry.get("phase_id"));
        assertEquals(2, geometry.get("group_id"));
        assertEquals(1, python.get("phase_id"));
        assertEquals(2, python.get("group_id"));
    }

    @Test
    void runtimeOverrideDoesNotReplaceReferenceJointRoi() {
        Map<String, Object> jointNorm = Map.of("x", 0.1, "y", 0.1, "width", 0.2, "height", 0.2);
        ReferenceSnapshot jointCameraRef = new ReferenceSnapshot("product", Map.of(
                "width", 2448,
                "height", 2048,
                "client_reference_bundle", true,
                "joint_camera_id", 2,
                "joint_roi_norm", jointNorm,
                "shm_name", "ref_shm",
                "shm_offset", 0,
                "stride", 7344
        ));

        Map<String, Object> header = new HashMap<>(BinaryInspectHeaders.geometryInspectHeader(
                2, capture, jointCameraRef, null, null));
        @SuppressWarnings("unchecked")
        Map<String, Object> referenceJointRoi = (Map<String, Object>) header.get("jointRoi");

        GeometryRuntimeConfig runtimeConfig = new GeometryRuntimeConfig();
        runtimeConfig.replaceAllFromClient(Map.of(
                "jointRoi", Map.of("x", 0, "y", 0, "width", 50, "height", 50)
        ));
        runtimeConfig.applyToGeometryHeader(header);

        assertNotNull(referenceJointRoi);
        assertNotNull(header.get("jointRoi"));
        assertTrueMapsEqual(referenceJointRoi, header.get("jointRoi"));
    }

    @Test
    void pythonHeaderDoesNotOverrideProfileThresholdWithYamlFallback() {
        Map<String, Object> header = BinaryInspectHeaders.pythonInspectHeader(
                1,
                "bench-lan1",
                "surface",
                capture,
                null,
                Map.of("fallback_threshold", 0.45),
                false
        );

        assertFalse(header.containsKey("threshold"));
    }

    @Test
    void explicitRuntimeThresholdCanStillOverrideProfileThreshold() {
        Map<String, Object> header = new HashMap<>(BinaryInspectHeaders.pythonInspectHeader(
                1,
                "bench-lan1",
                "surface",
                capture,
                null,
                Map.of("fallback_threshold", 0.45),
                false
        ));
        GeometryRuntimeConfig runtimeConfig = new GeometryRuntimeConfig();
        runtimeConfig.replaceAllFromClient("bench-lan1", Map.of("threshold", 0.07));

        runtimeConfig.applyToPythonHeader(header, Map.of("fallback_threshold", 0.45), "bench-lan1");

        assertEquals(0.07, header.get("threshold"));
    }

    private static void assertTrueMapsEqual(Object expected, Object actual) {
        if (!(expected instanceof Map<?, ?> expectedMap) || !(actual instanceof Map<?, ?> actualMap)) {
            throw new AssertionError("expected map values");
        }
        org.junit.jupiter.api.Assertions.assertEquals(expectedMap.get("x"), actualMap.get("x"));
        org.junit.jupiter.api.Assertions.assertEquals(expectedMap.get("y"), actualMap.get("y"));
        org.junit.jupiter.api.Assertions.assertEquals(expectedMap.get("width"), actualMap.get("width"));
        org.junit.jupiter.api.Assertions.assertEquals(expectedMap.get("height"), actualMap.get("height"));
    }
}
