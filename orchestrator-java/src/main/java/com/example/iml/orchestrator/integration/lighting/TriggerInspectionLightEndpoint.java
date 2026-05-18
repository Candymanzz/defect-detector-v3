package com.example.iml.orchestrator.integration.lighting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * LightServer (COM): {@code POST /api/light/trigger-inspection}, яркость 0…100.
 */
public final class TriggerInspectionLightEndpoint implements LightEndpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger log;
    private final String id;
    private final boolean enabled;
    private final URI triggerUri;
    private final URI statusUri;
    private final HttpClient httpClient;
    private final Duration timeout;
    private volatile boolean readyChecked;

    public TriggerInspectionLightEndpoint(
            Logger log,
            String id,
            boolean enabled,
            String baseUrl,
            String triggerPath,
            String statusPath,
            int timeoutMs
    ) {
        this.log = log;
        this.id = id;
        this.enabled = enabled;
        String path = triggerPath.startsWith("/") ? triggerPath : "/" + triggerPath;
        this.triggerUri = URI.create(baseUrl + path);
        String st = statusPath.startsWith("/") ? statusPath : "/" + statusPath;
        this.statusUri = URI.create(baseUrl + st);
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void ensureReady() {
        if (!enabled || readyChecked) {
            return;
        }
        synchronized (this) {
            if (readyChecked) {
                return;
            }
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(statusUri)
                        .timeout(timeout)
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    readyChecked = true;
                    return;
                }
            } catch (Exception ignored) {
            }
            log.warn("light endpoint {} status not ready at {}", id, statusUri);
            readyChecked = true;
        }
    }

    @Override
    public void trigger(int cameraId, long frameId, String phase, int brightnessPercent, int durationMs) throws Exception {
        if (!enabled) {
            return;
        }
        ensureReady();
        int brightness = LightBrightnessScale.toComControllerPercent(brightnessPercent);
        byte[] body = MAPPER.writeValueAsBytes(Map.of(
                "cameraId", cameraId,
                "frameId", frameId,
                "phase", phase == null ? "capture" : phase,
                "brightness", brightness,
                "durationMs", durationMs
        ));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(triggerUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(id + " trigger failed status=" + response.statusCode() + " body=" + response.body());
        }
        logSimulatedIfNeeded(response.body());
    }

    private void logSimulatedIfNeeded(String body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root.path("simulated").asBoolean(false)) {
                log.warn("light {}: simulated mode (no COM pulse)", id);
            }
        } catch (Exception ignored) {
        }
    }
}
