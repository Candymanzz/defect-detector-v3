package com.example.iml.orchestrator.integration.clientws.routing;

import com.example.iml.orchestrator.integration.clientws.application.ClientWsApplicationContext;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsException;
import com.example.iml.orchestrator.integration.clientws.handler.FpZonesUpdateWsHandler;
import com.example.iml.orchestrator.integration.clientws.handler.LightBrightnessWsHandler;
import com.example.iml.orchestrator.integration.clientws.handler.PreviewPauseWsHandler;
import com.example.iml.orchestrator.integration.clientws.handler.PreviewResumeWsHandler;
import com.example.iml.orchestrator.integration.clientws.handler.ReferenceBundleWsHandler;
import com.example.iml.orchestrator.integration.clientws.handler.SetActiveReferenceViewWsHandler;
import com.example.iml.orchestrator.integration.clientws.handler.StreamStartWsHandler;
import com.example.iml.orchestrator.integration.clientws.handler.StreamStopWsHandler;
import com.example.iml.orchestrator.integration.clientws.util.WsTextUtil;
import com.example.iml.orchestrator.integration.clientws.protocol.WsMessageTypes;
import com.fasterxml.jackson.databind.JsonNode;
import org.java_websocket.WebSocket;

import java.util.Optional;

/**
 * Front Controller WebSocket: маршрутизация по полю {@code type}.
 * Видеопоток: {@link WsMessageTypes#CLIENT_STREAM_START} / {@link WsMessageTypes#CLIENT_STREAM_STOP};
 * видео — HTTP {@code GET /api/camera/{id}/stream.mjpeg} (после stream_start).
 */
public final class WsFrontController {

    private final ClientWsApplicationContext application;
    private final WsRouter router;

    public WsFrontController(ClientWsApplicationContext application) {
        this.application = application;
        this.router = buildRouter();
    }

    public void dispatch(WebSocket connection, JsonNode envelope, String messageType) {
        String normalizedType = messageType == null ? "" : messageType.trim();
        application.log().info("client_ws inbound type={}", normalizedType);
        Optional<WsMessageHandler> handler = router.match(normalizedType);
        if (handler.isEmpty()) {
            if (WsMessageTypes.CLIENT_PREVIEW_PAUSE.equalsIgnoreCase(normalizedType)) {
                handler = Optional.of(new PreviewPauseWsHandler());
            } else if (WsMessageTypes.CLIENT_PREVIEW_RESUME.equalsIgnoreCase(normalizedType)) {
                handler = Optional.of(new PreviewResumeWsHandler());
            }
        }
        if (handler.isEmpty()) {
            application.outbound().sendError(
                    connection,
                    envelope,
                    "unknown_type",
                    "unsupported message type: " + messageType
            );
            return;
        }
        WsMessageContext ctx = new WsMessageContext(connection, envelope, normalizedType, application);
        try {
            handler.get().handle(ctx);
        } catch (ClientWsException e) {
            application.log().warn("client_ws handler {}: {}", normalizedType, e.getMessage());
            application.outbound().sendError(
                    connection,
                    envelope,
                    "handler_error",
                    WsTextUtil.truncate(e.getMessage(), 400)
            );
        }
    }

    private WsRouter buildRouter() {
        WsRouter router = new WsRouter();
        router.register(new WsRoute(WsMessageTypes.CLIENT_REFERENCE_BUNDLE, new ReferenceBundleWsHandler()));
        router.register(new WsRoute(WsMessageTypes.CLIENT_FP_ZONES_UPDATE, new FpZonesUpdateWsHandler()));
        router.register(new WsRoute(WsMessageTypes.CLIENT_SET_ACTIVE_REFERENCE_VIEW, new SetActiveReferenceViewWsHandler()));
        router.register(new WsRoute(WsMessageTypes.CLIENT_LIGHT_BRIGHTNESS, new LightBrightnessWsHandler()));
        router.register(new WsRoute(WsMessageTypes.CLIENT_PREVIEW_PAUSE, new PreviewPauseWsHandler()));
        router.register(new WsRoute(WsMessageTypes.CLIENT_PREVIEW_RESUME, new PreviewResumeWsHandler()));
        router.register(new WsRoute(WsMessageTypes.CLIENT_STREAM_START, new StreamStartWsHandler()));
        router.register(new WsRoute(WsMessageTypes.CLIENT_STREAM_STOP, new StreamStopWsHandler()));
        return router;
    }
}
