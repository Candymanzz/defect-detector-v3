package com.example.iml.orchestrator.integration.clientws.bundle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceBundleParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parseBundleAcceptsValidEnvelope() throws Exception {
        String json = """
                {
                  "protocol_version": 1,
                  "payload": {
                    "product_type": "bench",
                    "joint_view_index": 0,
                    "heatmap_width": 100,
                    "heatmap_height": 80,
                    "views": [
                      {
                        "frame": {
                          "camera_id": 0,
                          "frame_id": "42",
                          "shm_name": "/iml_cam_0_frame",
                          "width": 100,
                          "height": 80,
                          "stride": 300
                        },
                        "interest_roi": { "x": 10, "y": 10, "width": 50, "height": 40 },
                        "interest_polygon_norm": [
                          { "x": 0.1, "y": 0.1 },
                          { "x": 0.9, "y": 0.1 },
                          { "x": 0.5, "y": 0.9 }
                        ],
                        "joint_roi": { "x": 20, "y": 20, "width": 30, "height": 30 }
                      }
                    ],
                    "fp_zones": []
                  }
                }
                """;

        JsonNode envelope = MAPPER.readTree(json);
        ReferenceBundleParser.Result result = ReferenceBundleParser.parseBundle(envelope, 1, List.of(0));

        assertInstanceOf(ReferenceBundleParser.Result.Ok.class, result);
        ReferenceBundleSnapshot snapshot = ((ReferenceBundleParser.Result.Ok) result).snapshot();
        assertEquals("bench", snapshot.productType());
        assertEquals(1, snapshot.views().size());
        assertEquals(0, snapshot.views().get(0).frame().cameraId());
    }

    @Test
    void parseBundleRejectsWrongProtocolVersion() throws Exception {
        JsonNode envelope = MAPPER.readTree("{\"protocol_version\": 9, \"payload\": {}}");
        ReferenceBundleParser.Result result = ReferenceBundleParser.parseBundle(envelope, 1, List.of(0));

        assertInstanceOf(ReferenceBundleParser.Result.Err.class, result);
        assertEquals("invalid_protocol_version", ((ReferenceBundleParser.Result.Err) result).code());
    }

    @Test
    void parseFpZonesPayloadRequiresThreePoints() throws Exception {
        JsonNode zones = MAPPER.readTree("""
                [
                  {
                    "id": "z1",
                    "points_norm_heatmap": [
                      { "x": 0.1, "y": 0.2 },
                      { "x": 0.3, "y": 0.4 },
                      { "x": 0.5, "y": 0.6 }
                    ],
                    "points_norm_ref": [
                      { "x": 0.1, "y": 0.2 },
                      { "x": 0.3, "y": 0.4 },
                      { "x": 0.5, "y": 0.6 }
                    ]
                  }
                ]
                """);

        List<FpZoneNorm> parsed = ReferenceBundleParser.parseFpZonesPayload(zones);

        assertEquals(1, parsed.size());
        assertEquals("z1", parsed.get(0).id());
        assertEquals(3, parsed.get(0).pointsNormHeatmap().size());
    }

    @Test
    void parseFpZonesPayloadRejectsTooFewPoints() throws Exception {
        JsonNode zones = MAPPER.readTree("""
                [{ "points_norm_heatmap": [{ "x": 0.1, "y": 0.2 }] }]
                """);

        BundleParseException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BundleParseException.class,
                () -> ReferenceBundleParser.parseFpZonesPayload(zones)
        );
        assertTrue(ex.getMessage().contains("min 3"));
    }
}
