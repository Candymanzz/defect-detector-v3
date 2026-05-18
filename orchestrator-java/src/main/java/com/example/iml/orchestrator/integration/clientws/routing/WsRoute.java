package com.example.iml.orchestrator.integration.clientws.routing;

import java.util.Objects;

/**
 * Маршрут: поле {@code type} → обработчик.
 */
public final class WsRoute {

    private final String messageType;
    private final WsMessageHandler handler;

    public WsRoute(String messageType, WsMessageHandler handler) {
        this.messageType = Objects.requireNonNull(messageType);
        this.handler = Objects.requireNonNull(handler);
    }

    public boolean matches(String type) {
        return messageType.equals(type);
    }

    public WsMessageHandler handler() {
        return handler;
    }
}
