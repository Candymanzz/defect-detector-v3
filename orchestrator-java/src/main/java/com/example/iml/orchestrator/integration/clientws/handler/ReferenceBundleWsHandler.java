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
            ctx.application().outbound().sendError(ctx.connection(), err.code(), err.message());
            return;
        }
        ReferenceBundleSnapshot snap = ((ReferenceBundleParser.Result.Ok) r).snapshot();
        try {
            ReferenceBundleLifecycleService.acceptBundle(ctx.application(), ctx.connection(), snap, ctx.envelope());
        } catch (ClientWsKopcheniSyncException e) {
            ctx.application().log().warn("client_ws kopcheni sync after bundle failed: {}", e.getMessage());
            ctx.application().outbound().sendError(
                    ctx.connection(),
                    "kopcheni_sync_failed",
                    com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger.truncate(e.getMessage(), 400)
            );
        }
    }
}
