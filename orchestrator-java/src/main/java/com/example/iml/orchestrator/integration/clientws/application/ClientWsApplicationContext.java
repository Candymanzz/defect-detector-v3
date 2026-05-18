package com.example.iml.orchestrator.integration.clientws.application;

import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger;
import com.example.iml.orchestrator.integration.clientws.service.ClientWsKopcheniBroadcaster;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsReferenceContext;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Зависимости WebSocket-слоя (DI для handlers).
 */
public final class ClientWsApplicationContext {

    private final Logger log;
    private final ClientWsConfig cfg;
    private final ClientWsReferenceContext referenceContext;
    private final AtomicReference<ClientWsSessionState> sessionState;
    private final ClientWsKopcheniBroadcaster kopcheniBroadcaster;
    private final WsOutboundMessenger outbound;

    public ClientWsApplicationContext(
            Logger log,
            ClientWsConfig cfg,
            ClientWsReferenceContext referenceContext,
            AtomicReference<ClientWsSessionState> sessionState,
            ClientWsKopcheniBroadcaster kopcheniBroadcaster,
            WsOutboundMessenger outbound
    ) {
        this.log = log;
        this.cfg = cfg;
        this.referenceContext = referenceContext;
        this.sessionState = sessionState;
        this.kopcheniBroadcaster = kopcheniBroadcaster;
        this.outbound = outbound;
    }

    public Logger log() {
        return log;
    }

    public ClientWsConfig cfg() {
        return cfg;
    }

    public ClientWsReferenceContext referenceContext() {
        return referenceContext;
    }

    public ClientWsSessionState sessionState() {
        return sessionState.get();
    }

    public void setSessionState(ClientWsSessionState state) {
        sessionState.set(state == null ? ClientWsSessionState.NO_REFERENCE : state);
    }

    public ClientWsKopcheniBroadcaster kopcheniBroadcaster() {
        return kopcheniBroadcaster;
    }

    public WsOutboundMessenger outbound() {
        return outbound;
    }
}
