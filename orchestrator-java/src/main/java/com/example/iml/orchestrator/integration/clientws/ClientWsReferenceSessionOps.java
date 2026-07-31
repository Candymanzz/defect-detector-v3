package com.example.iml.orchestrator.integration.clientws;

import com.example.iml.orchestrator.integration.clientws.application.ClientWsApplicationContext;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsKopcheniSyncException;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger;
import com.example.iml.orchestrator.integration.clientws.service.ClientWsKopcheniBroadcaster;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsReferenceContext;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Clears reference / inspection context for the client WS session.
 */
final class ClientWsReferenceSessionOps {

    private final Logger log;
    private final ClientWsReferenceContext referenceContext;
    private final ClientWsApplicationContext application;
    private final ClientWsKopcheniBroadcaster kopcheniBroadcaster;
    private final WsOutboundMessenger outbound;
    private final Consumer<Consumer<WebSocket>> broadcastOpenClients;
    private final Consumer<ClientWsSessionState> setSessionState;

    ClientWsReferenceSessionOps(
            Logger log,
            ClientWsReferenceContext referenceContext,
            ClientWsApplicationContext application,
            ClientWsKopcheniBroadcaster kopcheniBroadcaster,
            WsOutboundMessenger outbound,
            Consumer<Consumer<WebSocket>> broadcastOpenClients,
            Consumer<ClientWsSessionState> setSessionState
    ) {
        this.log = log;
        this.referenceContext = referenceContext;
        this.application = application;
        this.kopcheniBroadcaster = kopcheniBroadcaster;
        this.outbound = outbound;
        this.broadcastOpenClients = broadcastOpenClients;
        this.setSessionState = setSessionState;
    }

    void clearReferenceSession() {
        referenceContext.clear();
        PipelineReferenceRegistry registry = application.pipelineReferences();
        if (registry != null) {
            registry.clear();
        }
        try {
            kopcheniBroadcaster.broadcast(Map.of("op", "clear_inspection_context"));
        } catch (ClientWsKopcheniSyncException e) {
            log.warn("client_ws clear_inspection_context failed: {}", e.getMessage());
        }
        setSessionState.accept(ClientWsSessionState.NO_REFERENCE);
        broadcastOpenClients.accept(conn -> outbound.sendSessionState(conn, ClientWsSessionState.NO_REFERENCE));
        log.info("client_ws reference cleared — session_state=NO_REFERENCE inspection stopped");
    }
}
