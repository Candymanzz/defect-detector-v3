package com.example.iml.orchestrator.integration.clientws.handler;

import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsKopcheniSyncException;
import com.example.iml.orchestrator.integration.clientws.util.WsTextUtil;
import com.example.iml.orchestrator.integration.clientws.routing.WsMessageContext;
import com.example.iml.orchestrator.integration.clientws.routing.WsMessageHandler;
import com.example.iml.orchestrator.integration.clientws.sync.AnalisSurfaceClientWsSync;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code client.set_active_reference_view}
 */
public final class SetActiveReferenceViewWsHandler implements WsMessageHandler {

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
        int viewIndex = payload.path("view_index").asInt(-1);
        if (viewIndex < 0 || viewIndex > 4) {
            app.outbound().sendError(ctx.connection(), "invalid_view_index", "view_index must be 0..4");
            return;
        }
        String productType = app.referenceContext().snapshot().map(ReferenceBundleSnapshot::productType).orElse("");
        if (productType.isEmpty()) {
            app.outbound().sendError(ctx.connection(), "no_reference", "missing product_type context");
            return;
        }
        try {
            app.kopcheniBroadcaster().broadcast(AnalisSurfaceClientWsSync.setActiveReferenceView(productType, viewIndex));
        } catch (ClientWsKopcheniSyncException e) {
            app.log().warn("client_ws kopcheni set_active_reference_view failed: {}", e.getMessage());
            app.outbound().sendError(ctx.connection(), "kopcheni_sync_failed", WsTextUtil.truncate(e.getMessage(), 400));
            return;
        }
        app.referenceContext().setActiveReferenceViewIndex(viewIndex);
        app.outbound().sendActiveReferenceViewAck(ctx.connection(), ctx.envelope(), viewIndex);
    }
}
