package com.example.iml.orchestrator.integration.trigger.transport;

import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** HTTP управление Line0 / synthetic DI3 в IoInputMonitor. */
final class IoInputMonitorSyntheticCaptureClient {

    private final Logger log;
    private final String baseUrl;
    private final HttpClient httpClient;

    IoInputMonitorSyntheticCaptureClient(Logger log, String host, int port) {
        this.log = log;
        String safeHost = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        int safePort = port > 0 && port <= 65535 ? port : 9101;
        this.baseUrl = "http://" + safeHost + ":" + safePort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    boolean triggerLine0Pulse() {
        return postEmpty("/line0-pulse", "line0 pulse");
    }

    boolean triggerSyntheticDi3Capture() {
        return postEmpty("/synthetic-di3-capture", "synthetic DI3 capture");
    }

    private boolean postEmpty(String path, String label) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("io_input {} OK status={} body={}", label, response.statusCode(), response.body());
                return true;
            }
            log.warn(
                    "io_input {} failed status={} body={}",
                    label,
                    response.statusCode(),
                    response.body()
            );
            return false;
        } catch (Exception ex) {
            log.warn("io_input {} error: {}", label, ex.toString());
            return false;
        }
    }
}
