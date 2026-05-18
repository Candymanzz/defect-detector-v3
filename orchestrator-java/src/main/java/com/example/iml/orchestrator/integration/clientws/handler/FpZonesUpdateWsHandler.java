package com.example.iml.orchestrator.integration.clientws.handler;

import com.example.iml.orchestrator.integration.clientws.bundle.BundleParseException;
import com.example.iml.orchestrator.integration.clientws.bundle.FpZoneNorm;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleParser;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsKopcheniSyncException;
import com.example.iml.orchestrator.integration.clientws.util.WsTextUtil;
import com.example.iml.orchestrator.integration.clientws.routing.WsMessageContext;
import com.example.iml.orchestrator.integration.clientws.routing.WsMessageHandler;
import com.example.iml.orchestrator.integration.clientws.sync.AnalisSurfaceClientWsSync;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * {@code client.fp_zones_update}
 */
public final class FpZonesUpdateWsHandler implements WsMessageHandler {

    @Override
    public void handle(WsMessageContext ctx) {
        var app = ctx.application();
        if (!app.referenceContext().hasCommittedBundle()) {
            app.outbound().sendError(ctx.connection(), "no_reference", "accept client.reference_bundle first");
            return;
        }
        JsonNode payload = ctx.envelope().path("payload");
        if (!payload.isObject()) {
            app.outbound().sendError(ctx.connection(), "invalid_payload", "payload must be object");
            return;
        }
        int pv = payload.path("protocol_version").asInt(-1);
        if (pv != app.cfg().protocolVersion()) {
            app.outbound().sendError(
                    ctx.connection(),
                    "invalid_protocol_version",
                    "expected protocol_version=" + app.cfg().protocolVersion()
            );
            return;
        }
        int hw = payload.path("heatmap_width").asInt(0);
        int hh = payload.path("heatmap_height").asInt(0);
        if (hw <= 0 || hh <= 0) {
            app.outbound().sendError(ctx.connection(), "invalid_heatmap_size", "heatmap_width and heatmap_height must be positive");
            return;
        }
        List<FpZoneNorm> zones;
        try {
            zones = ReferenceBundleParser.parseFpZonesPayload(payload.path("fp_zones"));
        } catch (BundleParseException e) {
            app.outbound().sendError(ctx.connection(), e.code(), e.getMessage());
            return;
        }
        String productType = app.referenceContext().snapshot().map(ReferenceBundleSnapshot::productType).orElse("");
        if (productType.isEmpty()) {
            app.outbound().sendError(ctx.connection(), "no_reference", "missing product_type context");
            return;
        }
        try {
            app.kopcheniBroadcaster().broadcast(AnalisSurfaceClientWsSync.replaceFpZones(productType, hw, hh, zones));
        } catch (ClientWsKopcheniSyncException e) {
            app.log().warn("client_ws kopcheni replace_fp_zones failed: {}", e.getMessage());
            app.outbound().sendError(ctx.connection(), "kopcheni_sync_failed", WsTextUtil.truncate(e.getMessage(), 400));
            return;
        }
        app.referenceContext().applyFpZonesHotUpdate(hw, hh, zones);
        app.outbound().sendFpZonesAck(ctx.connection(), ctx.envelope(), true);
    }
}
