package com.example.iml.orchestrator.integration.clientws.bundle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class ReferenceBundleParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsOneCameraPerMatchingViewSlot() throws Exception {
        ReferenceBundleParser.Result result = ReferenceBundleParser.parseBundle(bundle(0, 1, 2, 3), 1);

        ReferenceBundleParser.Result.Ok ok = assertInstanceOf(ReferenceBundleParser.Result.Ok.class, result);
        assertEquals(4, ok.snapshot().views().size());
        for (int i = 0; i < 4; i++) {
            assertEquals(i, ok.snapshot().views().get(i).frame().cameraId());
        }
    }

    @Test
    void rejectsDuplicateCameraLayout() throws Exception {
        ReferenceBundleParser.Result result = ReferenceBundleParser.parseBundle(bundle(0, 0, 0, 0), 1);

        ReferenceBundleParser.Result.Err err = assertInstanceOf(ReferenceBundleParser.Result.Err.class, result);
        assertEquals("invalid_camera_layout", err.code());
    }

    private static JsonNode bundle(int... cameraIds) throws Exception {
        StringBuilder views = new StringBuilder();
        for (int i = 0; i < cameraIds.length; i++) {
            if (i > 0) {
                views.append(',');
            }
            views.append("""
                    {
                      "frame": {
                        "camera_id": %d,
                        "frame_id": "%d",
                        "shm_name": "/iml_cam_%d_frame",
                        "width": 2448,
                        "height": 2048,
                        "stride": 7344,
                        "shm_offset": 0,
                        "pixel_format": "bgr_u8",
                        "channels": 3
                      },
                      "interest_roi": {"x": 0, "y": 0, "width": 2448, "height": 2048},
                      "interest_polygon_norm": [
                        {"x": 0.0, "y": 0.0},
                        {"x": 1.0, "y": 0.0},
                        {"x": 1.0, "y": 1.0}
                      ],
                      "joint_roi": %s
                    }
                    """.formatted(cameraIds[i], i, cameraIds[i], i == 0
                    ? "{\"x\": 0, \"y\": 0, \"width\": 100, \"height\": 100}"
                    : "null"));
        }
        return JSON.readTree("""
                {
                  "type": "client.reference_bundle",
                  "protocol_version": 1,
                  "message_id": "test",
                  "payload": {
                    "product_type": "reference-product",
                    "joint_view_index": 0,
                    "heatmap_width": 2448,
                    "heatmap_height": 2048,
                    "views": [%s],
                    "fp_zones": []
                  }
                }
                """.formatted(views));
    }
}
