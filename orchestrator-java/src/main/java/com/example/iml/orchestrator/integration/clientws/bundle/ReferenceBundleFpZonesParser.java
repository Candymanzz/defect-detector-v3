package com.example.iml.orchestrator.integration.clientws.bundle;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Parsing of {@code fp_zones} arrays from reference bundle / fp_zones_update. */
final class ReferenceBundleFpZonesParser {

    private ReferenceBundleFpZonesParser() {
    }

    static List<FpZoneNorm> parseFpZones(JsonNode fpNode) throws BundleParseException {
        if (fpNode == null || !fpNode.isArray()) {
            throw new BundleParseException("invalid_fp_zones", "fp_zones must be array");
        }
        List<FpZoneNorm> zones = new ArrayList<>();
        for (int zi = 0; zi < fpNode.size(); zi++) {
            JsonNode z = fpNode.get(zi);
            if (z == null || !z.isObject()) {
                throw new BundleParseException("invalid_fp_zone", "fp_zones[" + zi + "] must be object");
            }
            String id = z.has("id") && !z.get("id").isNull() ? z.get("id").asText(null) : null;
            String note = z.has("note") && !z.get("note").isNull() ? z.get("note").asText("") : "";
            Integer cameraId = null;
            if (z.has("camera_id") && !z.get("camera_id").isNull()) {
                int parsedCameraId = z.get("camera_id").asInt(-1);
                if (parsedCameraId < 0) {
                    throw new BundleParseException(
                            "invalid_camera_id", "fp_zones[" + zi + "].camera_id must be >= 0");
                }
                cameraId = parsedCameraId;
            }
            JsonNode pts = z.path("points_norm_heatmap");
            if (!pts.isArray() || pts.size() < 3) {
                throw new BundleParseException(
                        "invalid_fp_polygon",
                        "fp_zones[" + zi + "].points_norm_heatmap min 3 points");
            }
            List<FpZoneNorm.PointNorm> points = new ArrayList<>();
            for (int pi = 0; pi < pts.size(); pi++) {
                JsonNode p = pts.get(pi);
                if (p == null || !p.isObject()) {
                    throw new BundleParseException(
                            "invalid_fp_point",
                            "fp_zones[" + zi + "].points[" + pi + "] must be object");
                }
                double nx = p.path("x").asDouble(Double.NaN);
                double ny = p.path("y").asDouble(Double.NaN);
                if (nx < 0 || nx > 1 || ny < 0 || ny > 1 || Double.isNaN(nx) || Double.isNaN(ny)) {
                    throw new BundleParseException(
                            "fp_point_out_of_range",
                            "fp_zones[" + zi + "] point must be in [0,1]");
                }
                points.add(new FpZoneNorm.PointNorm(nx, ny));
            }
            zones.add(new FpZoneNorm(id, note, cameraId, List.copyOf(points)));
        }
        return zones;
    }
}
