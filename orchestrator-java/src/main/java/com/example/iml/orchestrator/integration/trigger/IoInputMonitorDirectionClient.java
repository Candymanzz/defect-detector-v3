package com.example.iml.orchestrator.integration.trigger;

import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Прокидывает UI-ход (forward/reverse) в IoInputMonitor HTTP API.
 */
public final class IoInputMonitorDirectionClient {

    private final Logger log;
    private final String putUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .build();

    public IoInputMonitorDirectionClient(Logger log, String baseUrl) {
        this.log = log;
        String normalized = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.putUrl = normalized.isEmpty() ? "" : normalized + "/line-direction";
    }

    public static IoInputMonitorDirectionClient fromIntegration(Logger log, Map<String, Object> integration) {
        String url = "http://127.0.0.1:9101";
        if (integration != null) {
            Object raw = integration.get("io_input_monitor_direction_url");
            if (raw != null) {
                String configured = String.valueOf(raw).trim();
                if (!configured.isEmpty()) {
                    url = configured;
                }
            }
        }
        return new IoInputMonitorDirectionClient(log, url);
    }

    public boolean isConfigured() {
        return !putUrl.isEmpty();
    }

    public void publishDirection(String directionWire) {
        if (!isConfigured() || directionWire == null || directionWire.isBlank()) {
            return;
        }
        String body = "{\"direction\":\"" + directionWire.trim().toLowerCase() + "\"}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(putUrl))
                    .timeout(Duration.ofMillis(800))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("io_input_monitor direction synced url={} direction={}", putUrl, directionWire);
            } else {
                log.warn(
                        "io_input_monitor direction sync failed status={} body={}",
                        response.statusCode(),
                        response.body()
                );
            }
        } catch (Exception e) {
            log.warn("io_input_monitor direction sync error url={}: {}", putUrl, e.getMessage());
        }
    }
}
