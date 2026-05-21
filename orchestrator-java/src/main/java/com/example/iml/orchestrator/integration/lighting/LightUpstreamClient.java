package com.example.iml.orchestrator.integration.lighting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Прокси HTTP-запросов к LightServer.v3 (тот же {@code base_url}, что у endpoints в конфиге).
 */
public final class LightUpstreamClient {

    public record UpstreamResponse(int statusCode, String body) {
        public boolean ok() {
            return statusCode / 100 == 2;
        }
    }

    private final String baseUrl;
    private final HttpClient httpClient;
    private final Duration timeout;

    public LightUpstreamClient(LightServersConfig cfg) {
        this.baseUrl = cfg.upstreamBaseUrl();
        int timeoutMs = cfg == null ? 1500 : Math.max(100, cfg.timeoutMs());
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public UpstreamResponse get(String pathAndQuery) throws Exception {
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
