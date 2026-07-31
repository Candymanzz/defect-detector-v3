package com.example.iml.orchestrator.integration.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Optional;

public final class HttpFrontController {

    private final HttpRouter router;

    public HttpFrontController(HttpApplicationContext ctx) {
        this.router = HttpFrontRouteBuilder.buildRouter(ctx);
    }

    public void dispatch(HttpExchange exchange) throws IOException {
        HttpRequestContext req = new HttpRequestContext(exchange);
        String method = req.method();
        String path = req.path();

        if ("OPTIONS".equalsIgnoreCase(method) && path.startsWith("/api/")) {
            HttpResponses.corsPreflight(exchange, "GET, POST, PUT, PATCH, DELETE, OPTIONS");
            return;
        }

        Optional<HttpController> handler = router.match(method, path);
        if (handler.isPresent()) {
            try {
                handler.get().handle(req);
            } catch (Exception e) {
                if (!req.exchange().getResponseHeaders().containsKey("Content-type")) {
                    HttpResponses.sendJsonError(req, 500, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            return;
        }
        HttpResponses.notFound(req);
    }
}
