package com.example.iml.orchestrator.integration.clientws;

import com.example.iml.orchestrator.integration.clientws.application.ClientWsApplicationContext;
import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger;
import com.example.iml.orchestrator.integration.clientws.routing.WsFrontController;
import com.example.iml.orchestrator.integration.clientws.service.ClientWsKopcheniBroadcaster;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsReferenceContext;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Assembled collaborator graph for {@link ClientWebSocketServer}.
 */
final class ClientWsServerParts {

    final ClientWsCollaboratorWiring wiring;
    final ClientWsSessionLifecycle lifecycle;
    final ClientWsOutboundNotifier notifier;
    final ClientWsInboundMessageRouter inboundRouter;
    final ClientWsReferenceSessionOps referenceSessionOps;

    private ClientWsServerParts(
            ClientWsCollaboratorWiring wiring,
            ClientWsSessionLifecycle lifecycle,
            ClientWsOutboundNotifier notifier,
            ClientWsInboundMessageRouter inboundRouter,
            ClientWsReferenceSessionOps referenceSessionOps
    ) {
        this.wiring = wiring;
        this.lifecycle = lifecycle;
        this.notifier = notifier;
        this.inboundRouter = inboundRouter;
        this.referenceSessionOps = referenceSessionOps;
    }

    static ClientWsServerParts create(
            Logger log,
            ClientWsConfig cfg,
            ClientWsReferenceContext referenceContext,
            Consumer<CameraStreamService> cameraStreamServiceSink,
            Supplier<CameraStreamService> cameraStreamService,
            Supplier<Collection<WebSocket>> connections
    ) {
        AtomicReference<ClientWsSessionState> sessionState =
                new AtomicReference<>(ClientWsSessionState.NO_REFERENCE);
        ClientWsKopcheniBroadcaster kopcheniBroadcaster = new ClientWsKopcheniBroadcaster(log, cfg);
        WsOutboundMessenger outbound = new WsOutboundMessenger(log, cfg, referenceContext, sessionState::get);
        ClientWsApplicationContext application = new ClientWsApplicationContext(
                log, cfg, referenceContext, sessionState, kopcheniBroadcaster, outbound);
        WsFrontController frontController = new WsFrontController(application);
        ClientWsCollaboratorWiring wiring = new ClientWsCollaboratorWiring(
                application, kopcheniBroadcaster, outbound, cameraStreamServiceSink);
        ClientWsOutboundNotifier notifier = new ClientWsOutboundNotifier(outbound, connections);
        return new ClientWsServerParts(
                wiring,
                new ClientWsSessionLifecycle(log, cfg, outbound, connections, cameraStreamService),
                notifier,
                new ClientWsInboundMessageRouter(log, frontController, outbound),
                new ClientWsReferenceSessionOps(
                        log, referenceContext, application, kopcheniBroadcaster, outbound,
                        notifier::broadcastOpenClients, wiring::setSessionState)
        );
    }
}
