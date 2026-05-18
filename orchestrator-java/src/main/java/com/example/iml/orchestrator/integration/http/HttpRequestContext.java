package com.example.iml.orchestrator.integration.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * Обёртка над {@link HttpExchange} для контроллеров.
 */
public final class HttpRequestContext {

    private final HttpExchange exchange;

    public HttpRequestContext(HttpExchange exchange) {
        this.exchange = exchange;
    }

    public HttpExchange exchange() {
        return exchange;
    }

    public String method() {
        return exchange.getRequestMethod();
    }

    public String path() {
        return exchange.getRequestURI().getPath();
    }

    public URI uri() {
        return exchange.getRequestURI();
    }

    public String query() {
        return exchange.getRequestURI().getQuery();
    }

    public byte[] readBody() throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return in.readAllBytes();
        }
    }
}
