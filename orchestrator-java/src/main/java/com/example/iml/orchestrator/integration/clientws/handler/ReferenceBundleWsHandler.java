package com.example.iml.orchestrator.integration.clientws.handler;

import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleParser;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsKopcheniSyncException;
import com.example.iml.orchestrator.integration.clientws.routing.WsMessageContext;
import com.example.iml.orchestrator.integration.clientws.routing.WsMessageHandler;
import com.example.iml.orchestrator.integration.clientws.service.ReferenceBundleLifecycleService;

/**
 * {@code client.reference_bundle}
 */
public final class ReferenceBundleWsHandler implements WsMessageHandler {

    @Override
    public void handle(WsMessageContext ctx) throws ClientWsKopcheniSyncException {
        ReferenceBundleParser.Result r = ReferenceBundleParser.parseBundle(
                ctx.envelope(),
                ctx.application().cfg().protocolVersion()
        );
        if (r instanceof ReferenceBundleParser.Result.Err err) {
            ctx.application().outbound().sendError(ctx.connection(), ctx.envelope(), err.code(), err.message());
            return;
        }
        ReferenceBundleSnapshot parsed = ((ReferenceBundleParser.Result.Ok) r).snapshot();
        ReferenceBundleSnapshot snap = normalizeHeatmapSpaceForInspectScale(
                parsed,
                ctx.application().cfg().inspectScale()
        );
        try {
            ReferenceBundleLifecycleService.acceptBundle(ctx.application(), ctx.connection(), snap, ctx.envelope());
        } catch (ClientWsKopcheniSyncException e) {
            ctx.application().log().warn("client_ws kopcheni sync after bundle failed: {}", e.getMessage());
            ctx.application().outbound().sendError(
                    ctx.connection(),
                    ctx.envelope(),
                    "kopcheni_sync_failed",
                    com.example.iml.orchestrator.integration.clientws.util.WsTextUtil.truncate(e.getMessage(), 400)
            );
        }
    }

    private static ReferenceBundleSnapshot normalizeHeatmapSpaceForInspectScale(
            ReferenceBundleSnapshot snapshot,
            double inspectScale
    ) {
        if (!Double.isFinite(inspectScale) || inspectScale <= 0.0d || Math.abs(inspectScale - 1.0d) < 1e-6d) {
            return snapshot;
        }
        if (snapshot.views() == null || snapshot.views().isEmpty()) {
            return snapshot;
        }
        int baseW = Math.max(1, snapshot.views().get(0).frame().width());
        int baseH = Math.max(1, snapshot.views().get(0).frame().height());
        int scaledW = Math.max(1, (int) Math.round(baseW * inspectScale));
        int scaledH = Math.max(1, (int) Math.round(baseH * inspectScale));
        return new ReferenceBundleSnapshot(
                snapshot.productType(),
                snapshot.views(),
                snapshot.jointViewIndex(),
                scaledW,
                scaledH,
                snapshot.fpZones(),
                snapshot.acceptedAtEpochMs()
        );
    }
}
