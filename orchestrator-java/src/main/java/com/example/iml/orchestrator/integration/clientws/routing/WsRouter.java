package com.example.iml.orchestrator.integration.clientws.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Таблица маршрутов WebSocket (порядок регистрации важен).
 */
public final class WsRouter {

    private final List<WsRoute> routes = new ArrayList<>();

    public WsRouter register(WsRoute route) {
        routes.add(route);
        return this;
    }

    public Optional<WsMessageHandler> match(String messageType) {
        for (WsRoute route : routes) {
            if (route.matches(messageType)) {
                return Optional.of(route.handler());
            }
        }
        return Optional.empty();
    }
}
