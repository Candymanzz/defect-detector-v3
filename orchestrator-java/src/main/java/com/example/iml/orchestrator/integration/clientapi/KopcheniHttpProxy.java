package com.example.iml.orchestrator.integration.clientapi;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * Прокси HTTP-запросов фронта к локальному FastAPI kopcheniservice (FP zones, ROI, inspect и т.д.).
 */
public final class KopcheniHttpProxy {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private KopcheniHttpProxy() {
    }

    /**
     * {@code exchangePath} — путь запроса, например {@code /api/client/fp-zones}; суффикс после {@code /api/client}
     * подставляется к {@code baseUrl}.
     */
    public static void forward(HttpExchange ex, String baseUrl, String exchangePath) throws IOException {
        String suffix = stripClientPrefix(exchangePath);
        String query = ex.getRequestURI().getRawQuery();
        String target = baseUrl + suffix + (query == null || query.isEmpty() ? "" : "?" + query);
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        byte[] body = readRequestBody(ex);
        boolean noBody = "GET".equals(method)
                || "HEAD".equals(method)
                || body.length == 0 && ("DELETE".equals(method) || "OPTIONS".equals(method));
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(120))
                .method(method, noBody ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct != null && !ct.isBlank() && body.length > 0) {
            rb.header("Content-Type", ct);
        }
        rb.header("Accept", ex.getRequestHeaders().getFirst("Accept") != null ? ex.getRequestHeaders().getFirst("Accept") : "*/*");
        try {
            HttpResponse<byte[]> resp = CLIENT.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            String respCt = resp.headers().firstValue("Content-Type").orElse("application/octet-stream");
            ex.getResponseHeaders().set("Content-Type", respCt);
            byte[] out = resp.body() == null ? new byte[0] : resp.body();
            ex.sendResponseHeaders(resp.statusCode(), out.length);
            try (var os = ex.getResponseBody()) {
                os.write(out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJsonError(ex, 502, "kopcheni proxy interrupted");
        } catch (Exception e) {
            sendJsonError(ex, 502, "kopcheni proxy: " + e.getMessage());
        }
    }

    private static String stripClientPrefix(String path) {
        String p = path.startsWith("/") ? path : "/" + path;
        if (p.startsWith("/api/client")) {
            String rest = p.substring("/api/client".length());
            return rest.isEmpty() ? "/" : rest;
        }
        return p;
    }

    private static byte[] readRequestBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return in.readAllBytes();
        }
    }

    private static void sendJsonError(HttpExchange ex, int code, String msg) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        String json = "{\"error\":\"" + msg.replace("\"", "'") + "\"}";
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (var os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
