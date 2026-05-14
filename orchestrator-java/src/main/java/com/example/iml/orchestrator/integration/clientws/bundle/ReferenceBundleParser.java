package com.example.iml.orchestrator.integration.clientws.bundle;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Парсинг и валидация {@code client.reference_bundle} (JSON + ShmFrameRef, без пикселей).
 */
public final class ReferenceBundleParser {

    private ReferenceBundleParser() {
    }

    public sealed interface Result permits Result.Ok, Result.Err {
        record Ok(ReferenceBundleSnapshot snapshot) implements Result {
        }

        record Err(String code, String message) implements Result {
        }
    }

    public static Result parseBundle(JsonNode envelope, int expectedProtocolVersion) {
        try {
            return new Result.Ok(parseBundleOrThrow(envelope, expectedProtocolVersion));
        } catch (BundleParseException e) {
            return new Result.Err(e.code(), e.getMessage());
        }
    }

    /**
     * Парсинг только массива {@code fp_zones} (как в пакете эталонов) — для {@code client.fp_zones_update}.
     */
    public static List<FpZoneNorm> parseFpZonesPayload(JsonNode fpNode) throws BundleParseException {
        return parseFpZones(fpNode);
    }

    private static ReferenceBundleSnapshot parseBundleOrThrow(JsonNode envelope, int expectedProtocolVersion)
            throws BundleParseException {
        if (envelope == null || !envelope.isObject()) {
            throw new BundleParseException("invalid_envelope", "root must be object");
        }
        int pv = envelope.path("protocol_version").asInt(-1);
        if (pv != expectedProtocolVersion) {
            throw new BundleParseException("invalid_protocol_version", "expected protocol_version=" + expectedProtocolVersion + " got " + pv);
        }
        JsonNode payload = envelope.path("payload");
        if (!payload.isObject()) {
            throw new BundleParseException("invalid_payload", "payload must be object");
        }
        String productType = textNonEmpty(payload, "product_type");
        if (productType == null) {
            throw new BundleParseException("invalid_product_type", "product_type required");
        }
        int jointViewIndex = payload.path("joint_view_index").asInt(-1);
        if (jointViewIndex < 0 || jointViewIndex > 4) {
            throw new BundleParseException("invalid_joint_view_index", "joint_view_index must be 0..4");
        }
        int heatmapW = payload.path("heatmap_width").asInt(0);
        int heatmapH = payload.path("heatmap_height").asInt(0);
        if (heatmapW <= 0 || heatmapH <= 0) {
            throw new BundleParseException("invalid_heatmap_size", "heatmap_width and heatmap_height must be positive");
        }
        JsonNode viewsNode = payload.path("views");
        if (!viewsNode.isArray() || viewsNode.size() != 5) {
            throw new BundleParseException("invalid_views", "views must be array of length 5");
        }
        List<ReferenceViewSlot> views = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            views.add(parseViewSlot(viewsNode.get(i), i, jointViewIndex));
        }
        List<FpZoneNorm> fpZones = parseFpZones(payload.path("fp_zones"));
        return new ReferenceBundleSnapshot(
                productType,
                List.copyOf(views),
                jointViewIndex,
                heatmapW,
                heatmapH,
                List.copyOf(fpZones),
                System.currentTimeMillis()
        );
    }

    private static ReferenceViewSlot parseViewSlot(JsonNode viewNode, int index, int jointViewIndex)
            throws BundleParseException {
        if (viewNode == null || !viewNode.isObject()) {
            throw new BundleParseException("invalid_view", "views[" + index + "] must be object");
        }
        String ctx = "views[" + index + "]";
        ShmFrameRefData frame = parseFrame(viewNode.path("frame"), ctx + ".frame");
        PixelRoi interest = parseRoi(viewNode.path("interest_roi"), ctx + ".interest_roi", frame.width(), frame.height());
        if (interest == null) {
            throw new BundleParseException("invalid_interest_roi", ctx + ".interest_roi invalid or out of frame");
        }
        boolean expectJoint = index == jointViewIndex;
        JsonNode jointNode = viewNode.get("joint_roi");
        PixelRoi joint = null;
        if (jointNode != null && !jointNode.isNull()) {
            if (!expectJoint) {
                throw new BundleParseException("invalid_joint_roi", "joint_roi only allowed on views[" + jointViewIndex + "]");
            }
            joint = parseRoi(jointNode, ctx + ".joint_roi", frame.width(), frame.height());
            if (joint == null) {
                throw new BundleParseException("invalid_joint_roi", ctx + ".joint_roi invalid or out of frame");
            }
        } else if (expectJoint) {
            throw new BundleParseException("missing_joint_roi", "joint_roi required on views[" + jointViewIndex + "]");
        }
        return new ReferenceViewSlot(frame, interest, joint);
    }

    private static ShmFrameRefData parseFrame(JsonNode n, String ctx) throws BundleParseException {
        if (n == null || !n.isObject()) {
            throw new BundleParseException("invalid_frame", ctx + " must be object");
        }
        int cameraId = n.path("camera_id").asInt(-1);
        if (cameraId < 0 || cameraId > 4) {
            throw new BundleParseException("invalid_camera_id", ctx + ".camera_id must be 0..4");
        }
        String frameId = textNonEmpty(n, "frame_id");
        if (frameId == null) {
            throw new BundleParseException("invalid_frame_id", ctx + ".frame_id required");
        }
        String shmName = textNonEmpty(n, "shm_name");
        if (shmName == null) {
            throw new BundleParseException("invalid_shm_name", ctx + ".shm_name required");
        }
        int width = n.path("width").asInt(0);
        int height = n.path("height").asInt(0);
        if (width <= 0 || height <= 0) {
            throw new BundleParseException("invalid_frame_size", ctx + " width/height must be positive");
        }
        int shmOffset = n.path("shm_offset").asInt(0);
        if (shmOffset < 0) {
            throw new BundleParseException("invalid_shm_offset", ctx + ".shm_offset must be >= 0");
        }
        String pixelFormat = n.has("pixel_format") && !n.get("pixel_format").isNull()
                ? n.get("pixel_format").asText("bgr_u8").trim()
                : "bgr_u8";
        if (pixelFormat.isEmpty()) {
            pixelFormat = "bgr_u8";
        }
        int channels = n.path("channels").asInt(0);
        if (channels <= 0) {
            channels = "gray_u8".equalsIgnoreCase(pixelFormat) ? 1 : 3;
        }
        int stride;
        if (n.has("stride") && !n.get("stride").isNull()) {
            stride = n.get("stride").asInt(0);
        } else {
            stride = width * channels;
        }
        if (stride < width * channels) {
            throw new BundleParseException("invalid_stride", ctx + ".stride too small for width/channels");
        }
        Long expiresAt = null;
        if (n.has("expires_at_ms") && n.get("expires_at_ms").isIntegralNumber()) {
            expiresAt = n.get("expires_at_ms").longValue();
        }
        Integer ttl = null;
        if (n.has("ttl_ms") && n.get("ttl_ms").isIntegralNumber()) {
            ttl = n.get("ttl_ms").intValue();
        }
        String readToken = null;
        if (n.has("read_token") && !n.get("read_token").isNull()) {
            readToken = n.get("read_token").asText(null);
            if (readToken != null && readToken.isBlank()) {
                readToken = null;
            }
        }
        return new ShmFrameRefData(
                cameraId,
                frameId,
                shmName,
                width,
                height,
                stride,
                shmOffset,
                pixelFormat,
                channels,
                expiresAt,
                ttl,
                readToken
        );
    }

    private static PixelRoi parseRoi(JsonNode n, String ctx, int frameW, int frameH) {
        if (n == null || !n.isObject()) {
            return null;
        }
        int x = n.path("x").asInt(Integer.MIN_VALUE);
        int y = n.path("y").asInt(Integer.MIN_VALUE);
        int w = n.path("width").asInt(0);
        int h = n.path("height").asInt(0);
        if (w <= 0 || h <= 0) {
            return null;
        }
        if (x < 0 || y < 0 || (long) x + w > frameW || (long) y + h > frameH) {
            return null;
        }
        return new PixelRoi(x, y, w, h);
    }

    private static List<FpZoneNorm> parseFpZones(JsonNode fpNode) throws BundleParseException {
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
            JsonNode pts = z.path("points_norm_heatmap");
            if (!pts.isArray() || pts.size() < 3) {
                throw new BundleParseException("invalid_fp_polygon", "fp_zones[" + zi + "].points_norm_heatmap min 3 points");
            }
            List<FpZoneNorm.PointNorm> points = new ArrayList<>();
            for (int pi = 0; pi < pts.size(); pi++) {
                JsonNode p = pts.get(pi);
                if (p == null || !p.isObject()) {
                    throw new BundleParseException("invalid_fp_point", "fp_zones[" + zi + "].points[" + pi + "] must be object");
                }
                double nx = p.path("x").asDouble(Double.NaN);
                double ny = p.path("y").asDouble(Double.NaN);
                if (nx < 0 || nx > 1 || ny < 0 || ny > 1 || Double.isNaN(nx) || Double.isNaN(ny)) {
                    throw new BundleParseException("fp_point_out_of_range", "fp_zones[" + zi + "] point must be in [0,1]");
                }
                points.add(new FpZoneNorm.PointNorm(nx, ny));
            }
            zones.add(new FpZoneNorm(id, note, List.copyOf(points)));
        }
        return zones;
    }

    private static String textNonEmpty(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        String t = parent.get(field).asText("").trim();
        return t.isEmpty() ? null : t;
    }
}
