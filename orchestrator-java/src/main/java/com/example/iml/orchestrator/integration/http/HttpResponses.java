package com.example.iml.orchestrator.integration.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Общие ответы HTTP (CORS, JSON-ошибки, тело).
 */
public final class HttpResponses {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpResponses() {
    }

    public static void corsJson(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    }

    public static void corsPreflight(HttpExchange ex, String allowMethods) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", allowMethods);
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Accept");
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    public static void send(HttpRequestContext ctx, int code, String contentType, byte[] body) throws IOException {
        send(ctx.exchange(), code, contentType, body);
    }

    public static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    public static void sendText(HttpRequestContext ctx, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        send(ctx, code, "text/plain; charset=utf-8", body);
    }

    public static void sendJson(HttpRequestContext ctx, int code, Object value) throws IOException {
        corsJson(ctx.exchange());
        byte[] body = JSON.writeValueAsBytes(value);
        send(ctx, code, "application/json; charset=utf-8", body);
    }

    public static void sendJsonError(HttpRequestContext ctx, int code, String message) throws IOException {
        corsJson(ctx.exchange());
        String json = "{\"error\":\"" + escapeJson(message) + "\"}";
        send(ctx, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    public static void methodNotAllowed(HttpRequestContext ctx) throws IOException {
        sendText(ctx, 405, "method not allowed\n");
    }

    public static void notFound(HttpRequestContext ctx) throws IOException {
        sendText(ctx, 404, "not found\n");
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }
}
