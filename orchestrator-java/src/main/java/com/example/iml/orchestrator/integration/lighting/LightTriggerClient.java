package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP-клиент к LightServer: триггер вспышки и ожидание готовности сервиса.
 */
public final class LightTriggerClient {
    private static final Logger log = LogManager.getLogger(LightTriggerClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TRIGGER_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MS = 200L;
    private static final long READY_WAIT_MS = 30_000L;

    private final boolean enabled;
    private final boolean failOnError;
    private final URI triggerUri;
    private final URI statusUri;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final int defaultBrightness;
    private final int defaultDurationMs;
    private final int settleDelayMs;
    private volatile boolean readyChecked;

    public static LightTriggerClient fromLightServerYaml(Map<String, Object> lightCfg) {
        boolean enabled = lightCfg != null && Boolean.parseBoolean(String.valueOf(lightCfg.getOrDefault("enabled", false)));
        String baseUrl = lightCfg == null ? "http://127.0.0.1:5079" : String.valueOf(lightCfg.getOrDefault("base_url", "http://127.0.0.1:5079"));
        String triggerPath = lightCfg == null ? "/api/light/trigger-inspection" : String.valueOf(lightCfg.getOrDefault("trigger_path", "/api/light/trigger-inspection"));
        int timeoutMs = YamlScalars.toInt(lightCfg == null ? null : lightCfg.get("timeout_ms"), 800);
        boolean failOnError = lightCfg != null && Boolean.parseBoolean(String.valueOf(lightCfg.getOrDefault("fail_on_error", false)));
        int brightness = YamlScalars.toInt(lightCfg == null ? null : lightCfg.get("brightness"), 100);
        int durationMs = YamlScalars.toInt(lightCfg == null ? null : lightCfg.get("duration_ms"), 100);
        int settleDelayMs = YamlScalars.toInt(lightCfg == null ? null : lightCfg.get("settle_delay_ms"), 75);
        return new LightTriggerClient(enabled, failOnError, baseUrl, triggerPath, timeoutMs, brightness, durationMs,
                settleDelayMs);
    }

    private LightTriggerClient(boolean enabled, boolean failOnError, String baseUrl, String triggerPath, int timeoutMs,
                       int defaultBrightness, int defaultDurationMs, int settleDelayMs) {
        this.enabled = enabled;
        this.failOnError = failOnError;
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.defaultBrightness = defaultBrightness;
        this.defaultDurationMs = defaultDurationMs;
        this.settleDelayMs = Math.max(0, settleDelayMs);
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = triggerPath.startsWith("/") ? triggerPath : "/" + triggerPath;
        this.triggerUri = URI.create(root + path);
        this.statusUri = URI.create(root + "/api/light/status");
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public void trigger(int cameraId, long frameId, String phase) {
        if (!enabled) {
            return;
        }
        waitUntilReady();
        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(Map.of(
                    "cameraId", cameraId,
                    "frameId", frameId,
                    "phase", phase,
                    "brightness", defaultBrightness,
                    "durationMs", defaultDurationMs
            ));
        } catch (Exception e) {
            if (failOnError) {
                throw new IllegalStateException("light trigger payload encode error", e);
            }
            log.warn("light trigger payload encode error: {}", e.getMessage());
            return;
        }

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_TRIGGER_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(triggerUri)
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    logSimulatedIfNeeded(response.body());
                    sleepSettle();
                    return;
                }
                String msg = "light trigger failed status=" + response.statusCode() + " body=" + response.body();
                lastError = new IllegalStateException(msg);
            } catch (Exception e) {
                lastError = new IllegalStateException("light trigger error", e);
            }

            if (attempt < MAX_TRIGGER_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (lastError == null) {
            return;
        }
        if (failOnError) {
            throw lastError;
        }
        log.warn("{} ({})", lastError.getMessage(), lastError.getCause() != null ? lastError.getCause() : "no cause");
    }

    private void logSimulatedIfNeeded(String body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            boolean simulated = root.path("simulated").asBoolean(false);
            if (simulated) {
                log.warn("Вспышка: LightServer в режиме симуляции (нет импульса на COM). "
                        + "Проверьте ComPort; для стенда без железа SimulateIfInitFailed=true.");
            }
        } catch (Exception ignored) {
        }
    }

    private void sleepSettle() {
        if (settleDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(settleDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void waitUntilReady() {
        if (readyChecked) {
            return;
        }
        synchronized (this) {
            if (readyChecked) {
                return;
            }
            long deadline = System.currentTimeMillis() + READY_WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
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
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (failOnError) {
                throw new IllegalStateException("light server is not ready at " + statusUri);
            }
            log.warn("light server is not ready at {} (продолжаем попытки trigger)", statusUri);
            readyChecked = true;
        }
    }
}
