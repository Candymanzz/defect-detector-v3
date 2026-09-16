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
        assertEquals(0, snapshot.phaseId());
        assertEquals(0, snapshot.groupId());
        assertEquals(1, snapshot.views().size());
        assertEquals(0, snapshot.views().get(0).frame().cameraId());
    }

    @Test
    void parseBundleCarriesExplicitPhaseAndGroup() throws Exception {
        JsonNode envelope = MAPPER.readTree("""
                {
                  "protocol_version": 1,
                  "payload": {
                    "product_type": "bench",
                    "phase_id": 1,
                    "group_id": 3,
                    "joint_view_index": 0,
                    "heatmap_width": 10,
                    "heatmap_height": 10,
                    "views": [{
                      "frame": {"camera_id": 5, "frame_id": "1", "shm_name": "/cam5", "width": 10, "height": 10, "stride": 30},
                      "interest_roi": {"x": 0, "y": 0, "width": 10, "height": 10},
                      "interest_polygon_norm": [
                        {"x": 0, "y": 0}, {"x": 1, "y": 0}, {"x": 1, "y": 1}
                      ],
                      "joint_roi": {"x": 1, "y": 1, "width": 8, "height": 8}
                    }],
                    "fp_zones": []
                  }
                }
                """);

        ReferenceBundleParser.Result result = ReferenceBundleParser.parseBundle(envelope, 1, List.of(5));
        assertInstanceOf(ReferenceBundleParser.Result.Ok.class, result);
        ReferenceBundleSnapshot snapshot = ((ReferenceBundleParser.Result.Ok) result).snapshot();
        assertEquals(1, snapshot.phaseId());
        assertEquals(3, snapshot.groupId());
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
