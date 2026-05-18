package com.example.iml.orchestrator.integration.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Таблица маршрутов для Front Controller (порядок регистрации важен: первое совпадение).
 */
public final class HttpRouter {

    private final List<HttpRoute> routes = new ArrayList<>();

    public HttpRouter register(HttpRoute route) {
        routes.add(route);
        return this;
    }

    public Optional<HttpController> match(String method, String path) {
        for (HttpRoute route : routes) {
            if (route.matches(method, path)) {
                return Optional.of(route.controller());
            }
        }
        return Optional.empty();
    }
}
