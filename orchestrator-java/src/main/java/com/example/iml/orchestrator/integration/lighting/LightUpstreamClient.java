package com.example.iml.orchestrator.integration.lighting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Адаптер API света: встроенный native-модуль или совместимый HTTP backend.
 */
public final class LightUpstreamClient {

    public record UpstreamResponse(int statusCode, String body) {
        public boolean ok() {
            return statusCode / 100 == 2;
        }
    }

    private final LightTriggerClient lightClient;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final Duration timeout;

    public LightUpstreamClient(LightServersConfig cfg) { this(cfg, null); }
    public LightUpstreamClient(LightServersConfig cfg, LightTriggerClient lightClient) {
        this.lightClient = lightClient;
        this.baseUrl = cfg.upstreamBaseUrl();
        int timeoutMs = cfg == null ? 1500 : Math.max(100, cfg.timeoutMs());
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public UpstreamResponse get(String pathAndQuery) throws Exception {
        if (lightClient != null && lightClient.isNativeLighting()) return nativeResponse("GET", pathAndQuery, java.util.Map.of());
        URI uri = URI.create(baseUrl + normalizePath(pathAndQuery));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new UpstreamResponse(response.statusCode(), response.body());
    }

    public UpstreamResponse post(String pathAndQuery, byte[] body) throws Exception {
        if (lightClient != null && lightClient.isNativeLighting()) {
            java.util.Map<String, Object> parsed = body == null || body.length == 0 ? java.util.Map.of()
                    : new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
            return nativeResponse("POST", pathAndQuery, parsed);
        }
        URI uri = URI.create(baseUrl + normalizePath(pathAndQuery));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new UpstreamResponse(response.statusCode(), response.body());
    }

    private UpstreamResponse nativeResponse(String method, String path, java.util.Map<String, Object> body) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try { return new UpstreamResponse(200, mapper.writeValueAsString(lightClient.nativeLightRequest(method, path, body))); }
        catch (IllegalArgumentException e) { return new UpstreamResponse(400, mapper.writeValueAsString(java.util.Map.of("success", false, "error", e.getMessage()))); }
        catch (IllegalStateException e) { return new UpstreamResponse(503, mapper.writeValueAsString(java.util.Map.of("success", false, "error", e.getMessage()))); }
    }
    public String baseUrl() {
        return baseUrl;
    }

    private static String normalizePath(String pathAndQuery) {
        if (pathAndQuery == null || pathAndQuery.isEmpty()) {
            return "/";
        }
        return pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
    }
}
