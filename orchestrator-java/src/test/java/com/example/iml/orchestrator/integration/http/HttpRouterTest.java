package com.example.iml.orchestrator.integration.http;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRouterTest {

    @Test
    void returnsFirstMatchingRoute() throws Exception {
        HttpRouter router = new HttpRouter();
        AtomicReference<String> hit = new AtomicReference<>("none");

        router.register(HttpRoute.exact("GET", "/api/a", ctx -> hit.set("a")));
        router.register(HttpRoute.exact("GET", "/api/b", ctx -> hit.set("b")));

        assertTrue(router.match("GET", "/api/b").isPresent());
        router.match("GET", "/api/b").get().handle(null);
        assertFalse(router.match("POST", "/api/b").isPresent());
        assertTrue(hit.get().equals("b"));
    }
}
