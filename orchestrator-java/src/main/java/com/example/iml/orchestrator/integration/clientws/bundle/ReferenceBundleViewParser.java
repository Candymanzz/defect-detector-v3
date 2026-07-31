package com.example.iml.orchestrator.integration.clientws.bundle;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Parsing of view slots, frames, ROIs, and normalized polygons. */
final class ReferenceBundleViewParser {

    private ReferenceBundleViewParser() {
    }

    static ReferenceViewSlot parseViewSlot(
            JsonNode viewNode,
            int index,
            int jointViewIndex,
            Set<Integer> allowedCameraIds
    ) throws BundleParseException {
        if (viewNode == null || !viewNode.isObject()) {
            throw new BundleParseException("invalid_view", "views[" + index + "] must be object");
        }
        String ctx = "views[" + index + "]";
        ShmFrameRefData frame = parseFrame(viewNode.path("frame"), ctx + ".frame", allowedCameraIds);
        PixelRoi interest = parseRoi(viewNode.path("interest_roi"), frame.width(), frame.height());
        if (interest == null) {
            throw new BundleParseException("invalid_interest_roi", ctx + ".interest_roi invalid or out of frame");
        }
        List<FpZoneNorm.PointNorm> interestPolygon = parseNormPolygonPoints(
                viewNode.path("interest_polygon_norm"),
                ctx + ".interest_polygon_norm",
                false
        );
        boolean expectJoint = index == jointViewIndex;
        JsonNode jointNode = viewNode.get("joint_roi");
        PixelRoi joint = null;
        if (jointNode != null && !jointNode.isNull()) {
            if (!expectJoint) {
                throw new BundleParseException(
                        "invalid_joint_roi", "joint_roi only allowed on views[" + jointViewIndex + "]");
            }
            joint = parseRoi(jointNode, frame.width(), frame.height());
            if (joint == null) {
                throw new BundleParseException("invalid_joint_roi", ctx + ".joint_roi invalid or out of frame");
            }
        }
        return new ReferenceViewSlot(frame, interest, joint, List.copyOf(interestPolygon));
    }

    static ShmFrameRefData parseFrame(JsonNode n, String ctx, Set<Integer> allowedCameraIds)
            throws BundleParseException {
        if (n == null || !n.isObject()) {
            throw new BundleParseException("invalid_frame", ctx + " must be object");
        }
        int cameraId = n.path("camera_id").asInt(-1);
        if (cameraId < 0 || !allowedCameraIds.contains(cameraId)) {
            throw new BundleParseException(
                    "invalid_camera_id", ctx + ".camera_id must be one of configured cameras");
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

    static PixelRoi parseRoi(JsonNode n, int frameW, int frameH) {
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

    static List<FpZoneNorm.PointNorm> parseNormPolygonPoints(JsonNode pts, String ctx, boolean required)
            throws BundleParseException {
        if (pts == null || pts.isNull()) {
            if (required) {
                throw new BundleParseException("invalid_interest_polygon", ctx + " required");
            }
            return List.of();
        }
        if (!pts.isArray()) {
            throw new BundleParseException("invalid_interest_polygon", ctx + " must be array");
        }
        if (pts.isEmpty()) {
            return List.of();
        }
        if (pts.size() < 3) {
            throw new BundleParseException("invalid_interest_polygon", ctx + " min 3 points");
        }
        List<FpZoneNorm.PointNorm> points = new ArrayList<>();
        for (int pi = 0; pi < pts.size(); pi++) {
            JsonNode p = pts.get(pi);
            if (p == null || !p.isObject()) {
                throw new BundleParseException(
                        "invalid_interest_polygon_point", ctx + "[" + pi + "] must be object");
            }
            double nx = p.path("x").asDouble(Double.NaN);
            double ny = p.path("y").asDouble(Double.NaN);
            if (nx < 0 || nx > 1 || ny < 0 || ny > 1 || Double.isNaN(nx) || Double.isNaN(ny)) {
                throw new BundleParseException(
                        "interest_polygon_point_out_of_range", ctx + "[" + pi + "] must be in [0,1]");
            }
            points.add(new FpZoneNorm.PointNorm(nx, ny));
        }
        return points;
    }

    static String textNonEmpty(JsonNode parent, String field) {
        if (!parent.has(field) || parent.get(field).isNull()) {
            return null;
        }
        String t = parent.get(field).asText("").trim();
        return t.isEmpty() ? null : t;
    }
}
