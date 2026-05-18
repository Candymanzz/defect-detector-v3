package com.example.iml.orchestrator.integration.http;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Маршрут: метод + точное совпадение пути или префикс.
 */
public final class HttpRoute {

    private final String method;
    private final MatchKind kind;
    private final String path;
    private final Pattern pathPattern;
    private final HttpController controller;

    private enum MatchKind {
        EXACT,
        PREFIX,
        REGEX
    }

    private HttpRoute(String method, MatchKind kind, String path, Pattern pathPattern, HttpController controller) {
        this.method = method == null ? "*" : method.toUpperCase();
        this.kind = kind;
        this.path = path;
        this.pathPattern = pathPattern;
        this.controller = Objects.requireNonNull(controller);
    }

    public static HttpRoute exact(String method, String path, HttpController controller) {
        return new HttpRoute(method, MatchKind.EXACT, path, null, controller);
    }

    public static HttpRoute prefix(String method, String pathPrefix, HttpController controller) {
        return new HttpRoute(method, MatchKind.PREFIX, pathPrefix, null, controller);
    }

    public static HttpRoute regex(String method, Pattern pattern, HttpController controller) {
        return new HttpRoute(method, MatchKind.REGEX, null, pattern, controller);
    }

    public boolean matches(String requestMethod, String requestPath) {
        if (!"*".equals(method) && !method.equalsIgnoreCase(requestMethod)) {
            return false;
        }
        return switch (kind) {
            case EXACT -> path.equals(requestPath);
            case PREFIX -> requestPath.startsWith(path);
            case REGEX -> pathPattern.matcher(requestPath).matches();
        };
    }

    public HttpController controller() {
        return controller;
    }
}
