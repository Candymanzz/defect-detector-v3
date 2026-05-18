package com.example.iml.orchestrator.integration.clientws.service;

import com.example.iml.orchestrator.integration.clientws.application.ClientWsApplicationContext;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsKopcheniSyncException;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.clientws.sync.AnalisSurfaceClientWsSync;
import com.fasterxml.jackson.databind.JsonNode;
import org.java_websocket.WebSocket;

/**
 * Принятие пакета эталонов и переходы session_state.
 */
public final class ReferenceBundleLifecycleService {

    private ReferenceBundleLifecycleService() {
    }

    public static void acceptBundle(
            ClientWsApplicationContext ctx,
            WebSocket conn,
            ReferenceBundleSnapshot snap,
            JsonNode requestRoot
    ) throws ClientWsKopcheniSyncException {
        ctx.kopcheniBroadcaster().broadcast(AnalisSurfaceClientWsSync.syncClientReferenceBundle(snap, 0));
        ctx.referenceContext().applyBundle(snap);
        transitionToOperational(ctx, conn, requestRoot);
        ctx.log().info(
                "client_ws reference bundle accepted product_type={} joint_view_index={} fp_zones={}",
                snap.productType(),
                snap.jointViewIndex(),
                snap.fpZones().size()
        );
    }

    public static void applyFromDraft(ClientWsApplicationContext ctx, WebSocket conn, ReferenceBundleSnapshot snap)
            throws ClientWsKopcheniSyncException {
        ctx.kopcheniBroadcaster().broadcast(AnalisSurfaceClientWsSync.syncClientReferenceBundle(snap, 0));
        ctx.referenceContext().applyBundle(snap);
        ctx.setSessionState(ClientWsSessionState.READY);
        if (conn != null && conn.isOpen()) {
            ctx.outbound().sendSessionState(conn, ClientWsSessionState.READY);
        }
        ctx.setSessionState(ClientWsSessionState.OPERATIONAL);
        if (conn != null && conn.isOpen()) {
            ctx.outbound().sendSessionState(conn, ClientWsSessionState.OPERATIONAL);
        }
        ctx.log().info(
                "client_ws reference bundle applied from draft product_type={} joint_view_index={} fp_zones={}",
                snap.productType(),
                snap.jointViewIndex(),
                snap.fpZones().size()
        );
    }

    private static void transitionToOperational(ClientWsApplicationContext ctx, WebSocket conn, JsonNode requestRoot) {
        ctx.setSessionState(ClientWsSessionState.READY);
        ctx.outbound().sendReferenceBundleAck(conn, requestRoot);
        ctx.outbound().sendSessionState(conn, ClientWsSessionState.READY);
        ctx.setSessionState(ClientWsSessionState.OPERATIONAL);
        ctx.outbound().sendSessionState(conn, ClientWsSessionState.OPERATIONAL);
    }
}
