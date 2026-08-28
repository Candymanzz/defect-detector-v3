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
        assertEquals(true, jointHeader.get("jointSeamSegmentationEnabled"));
        assertEquals(true, otherHeader.get("jointSeamSegmentationEnabled"));
    }

    @Test
    void withoutJointRoiSeamIsOffAndModeIsOff() {
        ReferenceSnapshot noJoint = new ReferenceSnapshot("product", Map.of(
                "width", 2448,
                "height", 2048,
                "client_reference_bundle", true,
                "interest_polygon_norm", List.of(
                        Map.of("x", 0.1, "y", 0.1),
                        Map.of("x", 0.9, "y", 0.1),
                        Map.of("x", 0.9, "y", 0.9)
                ),
                "shm_name", "ref_shm",
                "shm_offset", 0,
                "stride", 7344
        ));

        Map<String, Object> header = BinaryInspectHeaders.geometryInspectHeader(
                1, capture, noJoint, null, null);

        assertEquals(null, header.get("jointRoi"));
        assertEquals("off", header.get("jointMode"));
        assertEquals(false, header.get("jointSeamSegmentationEnabled"));
    }

    @Test
    void runtimeOverridesDoNotInjectJointWhenReferenceHasNoJoint() {
        ReferenceSnapshot noJoint = new ReferenceSnapshot("product", Map.of(
                "width", 2448,
                "height", 2048,
                "client_reference_bundle", true,
                "shm_name", "ref_shm",
                "shm_offset", 0,
                "stride", 7344
        ));
        Map<String, Object> header = new HashMap<>(BinaryInspectHeaders.geometryInspectHeader(
                1, capture, noJoint, null, null));

        GeometryRuntimeConfig runtimeConfig = new GeometryRuntimeConfig();
        runtimeConfig.replaceAllFromClient(Map.of(
                "maxShiftMm", 1.25,
                "jointSeamSegmentationEnabled", true,
                "jointRoi", Map.of("x", 0, "y", 0, "width", 50, "height", 50)
        ));
        runtimeConfig.applyToGeometryHeader(header);

        assertEquals(1.25, header.get("maxShiftMm"));
        assertEquals(null, header.get("jointRoi"));
        assertEquals("off", header.get("jointMode"));
        assertEquals(false, header.get("jointSeamSegmentationEnabled"));
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
    void geometryRuntimeDoesNotInjectAnomalyThresholdIntoPythonHeader() {
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

        assertFalse(header.containsKey("threshold"));
        Object algorithmParams = header.get("algorithm_params");
        if (algorithmParams instanceof Map<?, ?> params) {
            assertFalse(params.containsKey("threshold"));
        }
    }

    @Test
    void pythonTestFrameInspectHeaderUsesJpegPathAndEphemeralSimpleKnobs() {
        Map<String, Object> cap = new HashMap<>();
        cap.put("frame_id", 42L);
        cap.put("test_analyze", true);
        cap.put("test_analyze_job_id", "abc123def456");
        cap.put("test_frame_file_path", "/tmp/iml-test-pins/x/frame.jpg");
        cap.put("test_frame_cache_key", "0:42");
        cap.put("test_frame_image_url", "/api/client/inspection/test-pin/x/frame.jpg");
        cap.put("analysis_test_settings", Map.of(
                "mode", "simple",
                "knobs", Map.of("threshold", 0.3, "sensitivity", 0.7)
        ));
        cap.put("positioning_aligned", true);
        BinaryProtocol.Message captureMsg = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE, Map.copyOf(cap), new byte[0]);
        BinaryProtocol.Message geom = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE,
                Map.of("homographyRefToCurrent", List.of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.1)),
                new byte[0]
        );

        Map<String, Object> header = BinaryInspectHeaders.pythonTestFrameInspectHeader(
                0, "bench", "v1", captureMsg, geom, null, 512);

        assertEquals("inspect_test_frame", header.get("op"));
        assertEquals("/tmp/iml-test-pins/x/frame.jpg", header.get("file_path"));
        assertEquals("0:42", header.get("cache_key"));
        assertEquals(List.of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.1), header.get("alignment_h_ref_to_cur"));
        assertTrue(header.get("simple") instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> simple = (Map<String, Object>) header.get("simple");
        assertEquals(0.3, simple.get("threshold"));
        assertEquals(0.7, simple.get("sensitivity"));
        assertFalse(header.containsKey("pro"));
        assertNotNull(header.get("heatmap_u8_output_path"));
        assertEquals(512, header.get("heatmap_max_width"));
    }

    @Test
    void pythonTestFrameInspectHeaderForwardsSimpleAndDetailedKnobsTogether() {
        Map<String, Object> cap = new HashMap<>();
        cap.put("frame_id", 42L);
        cap.put("test_analyze", true);
        cap.put("test_analyze_job_id", "abc123def456");
        cap.put("test_frame_file_path", "/tmp/iml-test-pins/x/frame.jpg");
        cap.put("test_frame_cache_key", "0:42");
        cap.put("analysis_test_settings", Map.of(
                "simple", Map.of("threshold", 0.3, "sensitivity", 0.7),
                "detailed", Map.of(
                        "noise_tolerance", 50,
                        "scratch_sensitivity", 80,
                        "edge_suppression", 50,
                        "text_handling", 50,
                        "preprocess_strength", 100
                )
        ));
        BinaryProtocol.Message captureMsg = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE, Map.copyOf(cap), new byte[0]);
        BinaryProtocol.Message geom = new BinaryProtocol.Message(
                BinaryProtocol.MSG_RESPONSE,
                Map.of("homographyRefToCurrent", List.of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
                new byte[0]
        );

        Map<String, Object> header = BinaryInspectHeaders.pythonTestFrameInspectHeader(
                0, "bench", "v1", captureMsg, geom, null, 512);

        @SuppressWarnings("unchecked")
        Map<String, Object> simple = (Map<String, Object>) header.get("simple");
        @SuppressWarnings("unchecked")
        Map<String, Object> detailed = (Map<String, Object>) header.get("detailed");
        assertEquals(0.3, simple.get("threshold"));
        assertEquals(80, detailed.get("scratch_sensitivity"));
        assertFalse(header.containsKey("pro"));
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
