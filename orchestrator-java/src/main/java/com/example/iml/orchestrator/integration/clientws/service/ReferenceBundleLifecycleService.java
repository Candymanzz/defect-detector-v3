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
        syncBundleToDetectors(ctx, snap);
        applyBundleToPipeline(ctx, snap);
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
        syncBundleToDetectors(ctx, snap);
        applyBundleToPipeline(ctx, snap);
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

    private static void applyBundleToPipeline(ClientWsApplicationContext ctx, ReferenceBundleSnapshot snap)
            throws ClientWsKopcheniSyncException {
        if (ctx.pipelineReferences() == null || snap.views().isEmpty()) {
            throw new ClientWsKopcheniSyncException("pipeline reference registry is unavailable");
        }
        ctx.pipelineReferences().applyClientBundle(
                ctx.log(),
                snap,
                ctx.productTypeByCamera()
        );
    }

    private static void syncBundleToDetectors(ClientWsApplicationContext ctx, ReferenceBundleSnapshot snap)
            throws ClientWsKopcheniSyncException {
        ReferenceBundleSnapshot previous = ctx.referenceContext().snapshot()
                .map(existing -> new ReferenceBundleSnapshot(
                        existing.productType(),
                        existing.views(),
                        existing.jointViewIndex(),
                        ctx.referenceContext().effectiveHeatmapWidth(),
                        ctx.referenceContext().effectiveHeatmapHeight(),
                        ctx.referenceContext().effectiveFpZones(),
                        existing.acceptedAtEpochMs()
                ))
                .orElse(null);
        int attemptedViews = 0;
        try {
            for (int viewIndex = 0; viewIndex < snap.views().size(); viewIndex++) {
                int cameraId = snap.views().get(viewIndex).frame().cameraId();
                String productType = ctx.productTypeByCamera().getOrDefault(cameraId, snap.productType());
                ReferenceBundleSnapshot scoped = withProductType(snap, productType);
                attemptedViews = viewIndex + 1;
                ctx.kopcheniBroadcaster().broadcast(
                        AnalisSurfaceClientWsSync.syncClientReferenceBundle(scoped, viewIndex)
                );
            }
        } catch (ClientWsKopcheniSyncException syncError) {
            if (previous != null) {
                rollbackBundle(ctx, previous, attemptedViews);
            }
            throw syncError;
        }
    }

    private static void rollbackBundle(
            ClientWsApplicationContext ctx,
            ReferenceBundleSnapshot previous,
            int attemptedViews
    ) {
        int rollbackCount = Math.min(attemptedViews, previous.views().size());
        for (int viewIndex = rollbackCount - 1; viewIndex >= 0; viewIndex--) {
            int cameraId = previous.views().get(viewIndex).frame().cameraId();
            String productType = ctx.productTypeByCamera().getOrDefault(cameraId, previous.productType());
            try {
                ctx.kopcheniBroadcaster().broadcast(
                        AnalisSurfaceClientWsSync.syncClientReferenceBundle(
                                withProductType(previous, productType),
                                viewIndex
                        )
                );
            } catch (ClientWsKopcheniSyncException rollbackError) {
                ctx.log().error(
                        "client_ws reference rollback failed camera_id={}: {}",
                        cameraId,
                        rollbackError.getMessage()
                );
            }
        }
    }

    public static ReferenceBundleSnapshot withProductType(ReferenceBundleSnapshot snap, String productType) {
        return new ReferenceBundleSnapshot(
                productType,
                snap.views(),
                snap.jointViewIndex(),
                snap.heatmapWidth(),
                snap.heatmapHeight(),
                snap.fpZones(),
                snap.acceptedAtEpochMs()
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
