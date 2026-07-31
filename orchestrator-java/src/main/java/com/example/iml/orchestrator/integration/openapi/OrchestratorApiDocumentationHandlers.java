package com.example.iml.orchestrator.integration.openapi;

import com.example.iml.orchestrator.integration.http.HttpResponses;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * OpenAPI + Swagger UI для локальных HTTP-серверов оркестратора (JDK {@link HttpServer}).
 * Ресурсы: {@code /openapi/orchestrator-http.json}, {@code /openapi/swagger-ui.html}.
 */
public final class OrchestratorApiDocumentationHandlers {

    private static final String OPENAPI_RESOURCE = "/openapi/orchestrator-http.json";
    private static final String SWAGGER_HTML_RESOURCE = "/openapi/swagger-ui.html";

    private static volatile byte[] cachedOpenApiJson;
    private static volatile byte[] cachedSwaggerHtml;

    private OrchestratorApiDocumentationHandlers() {
    }

    public static void register(HttpServer server) {
        server.createContext("/openapi.json", OrchestratorApiDocumentationHandlers::handleOpenApi);
        server.createContext("/swagger", OrchestratorApiDocumentationHandlers::handleSwaggerUi);
    }

    private static void handleOpenApi(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            HttpResponses.corsJson(ex);
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!"GET".equalsIgnoreCase(method)) {
            sendMethodNotAllowed(ex);
            return;
        }
        byte[] body = openApiJson();
        if (body == null) {
            HttpResponses.send(ex, 404, "application/json", "{\"error\":\"openapi resource missing\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        HttpResponses.corsJson(ex);
        HttpResponses.send(ex, 200, "application/json; charset=utf-8", body);
    }

    private static void handleSwaggerUi(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            HttpResponses.corsJson(ex);
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!"GET".equalsIgnoreCase(method)) {
            sendMethodNotAllowed(ex);
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/swagger") && !path.equals("/swagger/")) {
            HttpResponses.send(ex, 404, "text/plain", "not found\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = swaggerHtml();
        if (body == null) {
            HttpResponses.send(ex, 404, "text/plain", "swagger ui resource missing\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        HttpResponses.corsJson(ex);
        HttpResponses.send(ex, 200, "text/html; charset=utf-8", body);
    }

    private static void sendMethodNotAllowed(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Allow", "GET, OPTIONS");
        HttpResponses.send(ex, 405, "text/plain", "method not allowed\n".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] openApiJson() {
        byte[] local = cachedOpenApiJson;
        if (local != null) {
            return local;
        }
        synchronized (OrchestratorApiDocumentationHandlers.class) {
            if (cachedOpenApiJson != null) {
                return cachedOpenApiJson;
            }
            byte[] loaded = readResourceBytes(OPENAPI_RESOURCE);
            cachedOpenApiJson = loaded;
            return loaded;
        }
    }

    private static byte[] swaggerHtml() {
        byte[] local = cachedSwaggerHtml;
        if (local != null) {
            return local;
        }
        synchronized (OrchestratorApiDocumentationHandlers.class) {
            if (cachedSwaggerHtml != null) {
                return cachedSwaggerHtml;
            }
            byte[] loaded = readResourceBytes(SWAGGER_HTML_RESOURCE);
            cachedSwaggerHtml = loaded;
            return loaded;
        }
    }

    private static byte[] readResourceBytes(String resource) {
        try (InputStream in = OrchestratorApiDocumentationHandlers.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
