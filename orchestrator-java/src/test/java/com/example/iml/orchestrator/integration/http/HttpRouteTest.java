package com.example.iml.orchestrator.integration.http;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRouteTest {

  private final HttpController ok = (ctx) -> {
  };

    @Test
    void exactRouteMatchesMethodAndPath() {
        HttpRoute route = HttpRoute.exact("GET", "/api/health", ok);

        assertTrue(route.matches("GET", "/api/health"));
        assertFalse(route.matches("POST", "/api/health"));
        assertFalse(route.matches("GET", "/api/other"));
    }

    @Test
    void prefixRouteMatchesNestedPaths() {
        HttpRoute route = HttpRoute.prefix("GET", "/api/camera/", ok);

        assertTrue(route.matches("GET", "/api/camera/0/stream.mjpeg"));
        assertFalse(route.matches("GET", "/api/camera"));
    }

    @Test
    void regexRouteMatchesPattern() {
        HttpRoute route = HttpRoute.regex("GET", Pattern.compile("/api/camera/\\d+/stream"), ok);

        assertTrue(route.matches("GET", "/api/camera/3/stream"));
        assertFalse(route.matches("GET", "/api/camera/x/stream"));
    }
}
