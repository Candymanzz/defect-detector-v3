package com.example.iml.orchestrator.integration.clientws.bundle;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    public static Result parseBundle(JsonNode envelope, int expectedProtocolVersion, List<Integer> configuredCameraIds) {
        try {
            return new Result.Ok(parseBundleOrThrow(envelope, expectedProtocolVersion, configuredCameraIds));
        } catch (BundleParseException e) {
            return new Result.Err(e.code(), e.getMessage());
        }
    }

    /**
     * Парсинг только массива {@code fp_zones} (как в пакете эталонов) — для {@code client.fp_zones_update}.
     */
    public static List<FpZoneNorm> parseFpZonesPayload(JsonNode fpNode) throws BundleParseException {
        return ReferenceBundleFpZonesParser.parseFpZones(fpNode);
    }

    private static ReferenceBundleSnapshot parseBundleOrThrow(
            JsonNode envelope,
            int expectedProtocolVersion,
            List<Integer> configuredCameraIds
    ) throws BundleParseException {
        List<Integer> cameraIds = normalizeConfiguredCameraIds(configuredCameraIds);
        if (envelope == null || !envelope.isObject()) {
            throw new BundleParseException("invalid_envelope", "root must be object");
        }
        int pv = envelope.path("protocol_version").asInt(-1);
        if (pv != expectedProtocolVersion) {
            throw new BundleParseException(
                    "invalid_protocol_version",
                    "expected protocol_version=" + expectedProtocolVersion + " got " + pv);
        }
        JsonNode payload = envelope.path("payload");
        if (!payload.isObject()) {
            throw new BundleParseException("invalid_payload", "payload must be object");
        }
        String productType = ReferenceBundleViewParser.textNonEmpty(payload, "product_type");
        if (productType == null) {
            throw new BundleParseException("invalid_product_type", "product_type required");
        }
        JsonNode viewsNode = payload.path("views");
        if (!viewsNode.isArray() || viewsNode.size() == 0) {
            throw new BundleParseException("invalid_views", "views must be a non-empty array");
        }
        int viewCount = viewsNode.size();
        int jointViewIndex = payload.path("joint_view_index").asInt(-1);
        if (jointViewIndex < 0 || jointViewIndex >= viewCount) {
            throw new BundleParseException("invalid_joint_view_index", "joint_view_index out of views range");
        }
        int heatmapW = payload.path("heatmap_width").asInt(0);
        int heatmapH = payload.path("heatmap_height").asInt(0);
        if (heatmapW <= 0 || heatmapH <= 0) {
            throw new BundleParseException(
                    "invalid_heatmap_size", "heatmap_width and heatmap_height must be positive");
        }
        Set<Integer> allowedCameraIds = new HashSet<>(cameraIds);
        Set<Integer> seenCameraIds = new HashSet<>();
        List<ReferenceViewSlot> views = new ArrayList<>(viewCount);
        for (int i = 0; i < viewCount; i++) {
            ReferenceViewSlot slot = ReferenceBundleViewParser.parseViewSlot(
                    viewsNode.get(i), i, jointViewIndex, allowedCameraIds);
            int cameraId = slot.frame().cameraId();
            if (!seenCameraIds.add(cameraId)) {
                throw new BundleParseException("invalid_views", "duplicate camera_id in views: " + cameraId);
            }
            views.add(slot);
        }
        List<FpZoneNorm> fpZones = ReferenceBundleFpZonesParser.parseFpZones(payload.path("fp_zones"));
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

    private static List<Integer> normalizeConfiguredCameraIds(List<Integer> configuredCameraIds)
            throws BundleParseException {
        if (configuredCameraIds == null || configuredCameraIds.isEmpty()) {
            throw new BundleParseException("invalid_views", "configured camera list is empty");
        }
        List<Integer> cameraIds = new ArrayList<>();
        for (Integer cameraId : configuredCameraIds) {
            if (cameraId == null || cameraId < 0 || cameraIds.contains(cameraId)) {
                continue;
            }
            cameraIds.add(cameraId);
        }
        if (cameraIds.isEmpty()) {
            throw new BundleParseException("invalid_views", "configured camera list is empty");
        }
        return List.copyOf(cameraIds);
    }
}
