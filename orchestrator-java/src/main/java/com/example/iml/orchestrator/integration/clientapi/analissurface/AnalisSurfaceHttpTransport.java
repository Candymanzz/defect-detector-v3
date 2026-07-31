package com.example.iml.orchestrator.integration.clientapi.analissurface;

import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Shared HTTP transport for analisSurface FastAPI (GET/POST/DELETE + JSON helpers).
 */
public final class AnalisSurfaceHttpTransport {

    private static final Logger LOG = LogManager.getLogger(AnalisSurfaceHttpTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final String name;
    private final String baseUrl;
    private final int commandTimeoutMs;

    public AnalisSurfaceHttpTransport(String name, String baseUrl, int commandTimeoutMs) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.commandTimeoutMs = commandTimeoutMs;
    }

    public String name() {
        return name;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public ObjectMapper mapper() {
        return MAPPER;
    }

    public HttpResponse<byte[]> httpGetRaw(String path) throws IOException {
        URI uri = URI.create(baseUrl + path);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(commandTimeoutMs))
                .GET()
                .header("Accept", "application/json")
                .build();
        return send(req);
    }

    public void httpDeleteRaw(String path) throws IOException {
        URI uri = URI.create(baseUrl + path);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(commandTimeoutMs))
                .DELETE()
                .header("Accept", "application/json")
                .build();
        try {
            HTTP.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(name + " DELETE interrupted", e);
        }
    }

    public HttpResponse<byte[]> httpPostJson(String path, Map<String, Object> jsonBody) throws IOException {
        byte[] json = MAPPER.writeValueAsBytes(jsonBody);
        URI uri = URI.create(baseUrl + path);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(commandTimeoutMs))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        HttpResponse<byte[]> resp = send(req);
        if (resp.statusCode() / 100 != 2) {
            logHttpFailure(path, jsonBody, resp);
        }
        return resp;
    }

    public HttpResponse<byte[]> send(HttpRequest req) throws IOException {
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(name + " HTTP interrupted", e);
        }
    }

    public static String errorMessage(String ctx, HttpResponse<byte[]> resp) {
        String msg = ctx + " HTTP " + resp.statusCode();
        byte[] body = resp.body();
        if (body != null && body.length > 0) {
            try {
                Map<String, Object> err = MAPPER.readValue(body, new TypeReference<>() {});
                Object detail = err.get("detail");
                if (detail != null) {
                    return msg + ": " + detail;
                }
            } catch (Exception ignored) {
            }
            msg = msg + ": " + new String(body, StandardCharsets.UTF_8);
        }
        return msg;
    }

    public static BinaryProtocol.Message errorMessageToMsg(HttpResponse<byte[]> resp, String ctx) {
        String msg = errorMessage(ctx, resp);
        return new BinaryProtocol.Message(
                BinaryProtocol.MSG_ERROR,
                Map.of("error", msg, "http_status", resp.statusCode()),
                new byte[0]
        );
    }

    public static Map<String, Object> readJson(byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            return Map.of();
        }
        return MAPPER.readValue(body, new TypeReference<>() {});
    }

    public void logHttpFailure(String path, Map<String, Object> requestBody, HttpResponse<byte[]> resp) {
        String req = safeJson(requestBody, 3000);
        String body = safeResponseBody(resp.body(), 3000);
        LOG.warn(
                "{} HTTP POST {} failed status={} request={} response={}",
                name,
                path,
                resp.statusCode(),
                req,
                body
        );
    }

    public static String safeJson(Map<String, Object> body, int maxLen) {
        try {
            return truncate(MAPPER.writeValueAsString(body), maxLen);
        } catch (Exception e) {
            return "<json_serialize_failed:" + e.getMessage() + ">";
        }
    }

    public static String safeResponseBody(byte[] body, int maxLen) {
        if (body == null || body.length == 0) {
            return "";
        }
        return truncate(new String(body, StandardCharsets.UTF_8), maxLen);
    }

    public static String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (maxLen <= 0 || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...";
    }

    public static String urlEncodePathSegment(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
