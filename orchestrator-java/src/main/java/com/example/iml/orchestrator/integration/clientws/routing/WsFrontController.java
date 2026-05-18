package com.example.iml.orchestrator.integration.clientws.routing;

import com.example.iml.orchestrator.integration.clientws.application.ClientWsApplicationContext;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsException;
import com.example.iml.orchestrator.integration.clientws.handler.FpZonesUpdateWsHandler;
import com.example.iml.orchestrator.integration.clientws.handler.ReferenceBundleWsHandler;
import com.example.iml.orchestrator.integration.clientws.handler.SetActiveReferenceViewWsHandler;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger;
import com.example.iml.orchestrator.integration.clientws.protocol.WsMessageTypes;
import com.fasterxml.jackson.databind.JsonNode;
import org.java_websocket.WebSocket;

import java.util.Optional;

/**
 * Front Controller WebSocket: маршрутизация по полю {@code type}.
 */
public final class WsFrontController {

    private final ClientWsApplicationContext application;
    private final WsRouter router;

    public WsFrontController(ClientWsApplicationContext application) {
        this.application = application;
        this.router = buildRouter();
    }

    public void dispatch(WebSocket connection, JsonNode envelope, String messageType) {
        application.log().info("client_ws inbound type={}", messageType);
        Optional<WsMessageHandler> handler = router.match(messageType);
        if (handler.isEmpty()) {
            application.outbound().sendError(connection, "unknown_type", "unsupported message type: " + messageType);
            return;
        }
        WsMessageContext ctx = new WsMessageContext(connection, envelope, messageType, application);
        try {
            handler.get().handle(ctx);
        } catch (ClientWsException e) {
            application.log().warn("client_ws handler {}: {}", messageType, e.getMessage());
            application.outbound().sendError(
                    connection,
                    "handler_error",
                    WsOutboundMessenger.truncate(e.getMessage(), 400)
            );
        }
    }

    private WsRouter buildRouter() {
        WsRouter router = new WsRouter();
        router.register(new WsRoute(WsMessageTypes.CLIENT_REFERENCE_BUNDLE, new ReferenceBundleWsHandler()));
        router.register(new WsRoute(WsMessageTypes.CLIENT_FP_ZONES_UPDATE, new FpZonesUpdateWsHandler()));
        router.register(new WsRoute(WsMessageTypes.CLIENT_SET_ACTIVE_REFERENCE_VIEW, new SetActiveReferenceViewWsHandler()));
        return router;
    }
}
