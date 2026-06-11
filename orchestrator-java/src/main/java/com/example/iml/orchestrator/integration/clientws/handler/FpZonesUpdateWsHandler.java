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
import java.util.ArrayList;

/**
 * {@code client.fp_zones_update}
 */
public final class FpZonesUpdateWsHandler implements WsMessageHandler {

    @Override
    public void handle(WsMessageContext ctx) {
        var app = ctx.application();
        if (!app.referenceContext().hasCommittedBundle()) {
            app.outbound().sendError(ctx.connection(), ctx.envelope(), "no_reference", "accept client.reference_bundle first");
            return;
        }
        JsonNode payload = ctx.envelope().path("payload");
        if (!payload.isObject()) {
            app.outbound().sendError(ctx.connection(), ctx.envelope(), "invalid_payload", "payload must be object");
            return;
        }
        int pv = payload.path("protocol_version").asInt(-1);
        if (pv != app.cfg().protocolVersion()) {
            app.outbound().sendError(
                    ctx.connection(),
                    ctx.envelope(),
                    "invalid_protocol_version",
                    "expected protocol_version=" + app.cfg().protocolVersion()
            );
            return;
        }
        int hw = payload.path("heatmap_width").asInt(0);
        int hh = payload.path("heatmap_height").asInt(0);
        if (hw <= 0 || hh <= 0) {
            app.outbound().sendError(
                    ctx.connection(),
                    ctx.envelope(),
                    "invalid_heatmap_size",
                    "heatmap_width and heatmap_height must be positive"
            );
            return;
        }
        int normalizedHw = normalizeDimForInspectScale(hw, app.cfg().inspectScale());
        int normalizedHh = normalizeDimForInspectScale(hh, app.cfg().inspectScale());
        List<FpZoneNorm> zones;
        try {
            zones = ReferenceBundleParser.parseFpZonesPayload(payload.path("fp_zones"));
        } catch (BundleParseException e) {
            app.outbound().sendError(ctx.connection(), ctx.envelope(), e.code(), e.getMessage());
            return;
        }
        ReferenceBundleSnapshot snapshot = app.referenceContext().snapshot().orElse(null);
        if (snapshot == null) {
            app.outbound().sendError(ctx.connection(), ctx.envelope(), "no_reference", "missing product_type context");
            return;
        }
        List<Integer> attemptedCameraIds = new ArrayList<>();
        try {
            for (var view : snapshot.views()) {
                int cameraId = view.frame().cameraId();
                String productType = app.productTypeByCamera().getOrDefault(cameraId, snapshot.productType());
                attemptedCameraIds.add(cameraId);
                app.kopcheniBroadcaster().broadcast(
                        AnalisSurfaceClientWsSync.replaceFpZones(
                                productType,
                                cameraId,
                                normalizedHw,
                                normalizedHh,
                                zones
                        )
                );
            }
        } catch (ClientWsKopcheniSyncException e) {
            rollbackFpZones(app, snapshot, attemptedCameraIds);
            app.log().warn("client_ws kopcheni replace_fp_zones failed: {}", e.getMessage());
            app.outbound().sendError(
                    ctx.connection(),
                    ctx.envelope(),
                    "kopcheni_sync_failed",
                    WsTextUtil.truncate(e.getMessage(), 400)
            );
            return;
        }
        app.referenceContext().applyFpZonesHotUpdate(normalizedHw, normalizedHh, zones);
        app.outbound().sendFpZonesAck(ctx.connection(), ctx.envelope(), true);
    }

    private static void rollbackFpZones(
            com.example.iml.orchestrator.integration.clientws.application.ClientWsApplicationContext app,
            ReferenceBundleSnapshot snapshot,
            List<Integer> attemptedCameraIds
    ) {
        List<FpZoneNorm> previousZones = app.referenceContext().effectiveFpZones();
        int previousWidth = app.referenceContext().effectiveHeatmapWidth();
        int previousHeight = app.referenceContext().effectiveHeatmapHeight();
        for (int i = attemptedCameraIds.size() - 1; i >= 0; i--) {
            int cameraId = attemptedCameraIds.get(i);
            String productType = app.productTypeByCamera().getOrDefault(cameraId, snapshot.productType());
            try {
                app.kopcheniBroadcaster().broadcast(
                        AnalisSurfaceClientWsSync.replaceFpZones(
                                productType,
                                cameraId,
                                previousWidth,
                                previousHeight,
                                previousZones
                        )
                );
            } catch (ClientWsKopcheniSyncException rollbackError) {
                app.log().error(
                        "client_ws FP rollback failed camera_id={}: {}",
                        cameraId,
                        rollbackError.getMessage()
                );
            }
        }
    }

    private static int normalizeDimForInspectScale(int dim, double inspectScale) {
        if (dim <= 0) {
            return 0;
        }
        if (!Double.isFinite(inspectScale) || inspectScale <= 0.0d || Math.abs(inspectScale - 1.0d) < 1e-6d) {
            return dim;
        }
        return Math.max(1, (int) Math.round(dim * inspectScale));
    }
}
