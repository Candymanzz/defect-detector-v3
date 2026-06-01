package com.example.iml.orchestrator.integration.lighting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * LightServer.v3 ComLightApply: {@code POST /api/com/light} с телом
 * {@code { "state": "on"|"off", "brightness": "100" }} — яркость в % через запятую по всем каналам банка
 * (COM1…COM3 из appsettings LightServer).
 * {@code GET /api/com/devices?ports=...} не вызываем перед вспышкой (долгое MVS enum).
 */
public final class ComIoLightEndpoint implements LightEndpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger log;
    private final String id;
    private final boolean enabled;
    private final URI lightUri;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final int[] defaultBrightnessRaw;

    public ComIoLightEndpoint(
            Logger log,
            String id,
            boolean enabled,
            String baseUrl,
            int timeoutMs,
            int[] defaultBrightnessRaw
    ) {
        this.log = log;
        this.id = id;
        this.enabled = enabled;
        String base = LightServerV3Http.normalizeBaseUrl(baseUrl);
        this.lightUri = URI.create(base + LightServerV3Http.PATH_COM_LIGHT);
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.defaultBrightnessRaw = defaultBrightnessRaw == null ? null : defaultBrightnessRaw.clone();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    /**
     * Не вызываем GET /api/com/devices перед вспышкой: перечисление MVS занимает 7–15 с,
     * держит {@code _sdkLock} в LightServer и мешает POST /api/com/light.
     */
    @Override
    public void ensureReady() {
        // см. комментарий к методу — перечисление устройств не делаем
    }

    @Override
    public void trigger(int cameraId, long frameId, String phase, int brightnessPercent, int durationMs) throws Exception {
        if (!enabled) {
            return;
        }
        String brightness = formatBrightnessCsv(brightnessPercent, defaultBrightnessRaw);
        postState("on", brightness);
        log.info("light {} COM bank on cam={} frame={} phase={} brightness={}",
                id, cameraId, frameId, phase, brightness);
    }

    @Override
    public void turnOffAll() throws Exception {
        if (!enabled) {
            return;
        }
        postState("off", null);
        log.info("light {} COM bank off", id);
    }

    @Override
    public void turnOffForCamera(int cameraId) throws Exception {
        if (!enabled) {
            return;
        }
        postState("off", null);
        log.info("light {} COM bank off cam={}", id, cameraId);
    }

    private void postState(String state, String brightnessCsv) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        if (brightnessCsv != null && !brightnessCsv.isBlank()) {
            body.put("brightness", brightnessCsv);
        }
        byte[] json = MAPPER.writeValueAsBytes(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(lightUri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        LightServerV3Http.requireLightCommandSuccess(id, "POST", LightServerV3Http.PATH_COM_LIGHT, response);
        if (response.body() != null && !response.body().isBlank()) {
            log.info("light {} COM {} -> {}", id, state, response.body());
        }
    }

    static String formatBrightnessCsv(int brightnessPercent, int[] brightnessRaw) {
        int percent = LightBrightnessScale.clampPercent(brightnessPercent);
        if (brightnessRaw == null || brightnessRaw.length == 0) {
            return Integer.toString(percent);
        }
        return IntStream.of(brightnessRaw)
                .mapToObj(v -> Integer.toString(LightBrightnessScale.toPercent(v, percent)))
                .collect(Collectors.joining(","));
    }
}
