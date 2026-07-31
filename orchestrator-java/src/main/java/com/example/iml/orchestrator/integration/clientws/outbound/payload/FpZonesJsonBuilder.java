package com.example.iml.orchestrator.integration.clientws.outbound.payload;

import com.example.iml.orchestrator.integration.clientws.bundle.FpZoneNorm;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundJson;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsReferenceContext;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds {@code fp_zones} JSON arrays from the active reference context.
 */
public final class FpZonesJsonBuilder {

    private FpZonesJsonBuilder() {
    }

    public static ArrayNode fpZonesJsonArray(ClientWsReferenceContext referenceContext, int cameraId) {
        ArrayNode arr = WsOutboundJson.JSON.createArrayNode();
        for (FpZoneNorm z : referenceContext.effectiveFpZones()) {
            if (z.cameraId() != null && z.cameraId() != cameraId) {
                continue;
            }
            ObjectNode zo = arr.addObject();
            if (z.id() != null && !z.id().isBlank()) {
                zo.put("id", z.id());
            }
            if (z.cameraId() != null) {
                zo.put("camera_id", z.cameraId());
            }
            zo.put("note", z.note() != null ? z.note() : "");
            ArrayNode pts = zo.putArray("points_norm_heatmap");
            for (FpZoneNorm.PointNorm p : z.pointsNormHeatmap()) {
                ObjectNode po = pts.addObject();
                po.put("x", p.x());
                po.put("y", p.y());
            }
        }
        return arr;
    }
}
