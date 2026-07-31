package com.example.iml.orchestrator.integration.lighting.client;

import com.example.iml.orchestrator.integration.lighting.LightingException;

import com.example.iml.orchestrator.integration.lighting.LightBrightnessScale;
import com.example.iml.orchestrator.integration.lighting.LightServerV3Http;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP transport and endpoint ownership for LightServer.v3.
 */
public final class LightServerHttpTransport {

    private static final Logger LOG = LogManager.getLogger(LightServerHttpTransport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String onUrl;
    private final String offUrl;
    private final String brightnessPairUrl;
    private final String brightnessSingleUrl;
    private final String flashBankUrl;
    private final String statusUrl;
    private final Duration timeout;
    private final Duration statusPollTimeout;
    private final HttpClient httpClient;

    public LightServerHttpTransport(LightServersConfig cfg, int timeoutMs) {
        this.onUrl = cfg.onUrl();
        this.offUrl = cfg.offUrl();
        this.brightnessPairUrl = cfg.brightnessPairUrl();
        this.brightnessSingleUrl = cfg.brightnessSingleUrl();
        this.flashBankUrl = LightServerV3Http.normalizeBaseUrl(cfg.upstreamBaseUrl())
                + LightServerV3Http.PATH_CAMERA_FLASH_BANK;
        this.statusUrl = cfg.statusUrl();
        this.timeout = Duration.ofMillis(timeoutMs);
        this.statusPollTimeout = Duration.ofMillis(Math.min(3000, Math.max(500, timeoutMs / 5)));
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public void postOn(int brightnessPercent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", "on");
        body.put("brightness", Integer.toString(LightBrightnessScale.clampPercent(brightnessPercent)));
        postJson(onUrl, body, "On");
    }

    public void postOff() {
        postJson(offUrl, Map.of("state", "off"), "Off");
    }

    public void pushCameraBrightness(LightServersConfig.CameraFlashSpec spec) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cameraNumber", spec.cameraNumber());
        if (spec.mode() == LightServersConfig.FlashMode.SINGLE) {
            body.put("power", spec.power255());
            postJson(brightnessSingleUrl, body, "brightness-single camera-" + spec.cameraId());
        } else {
            body.put("leftPower", spec.leftPower255());
            body.put("rightPower", spec.rightPower255());
            postJson(brightnessPairUrl, body, "brightness-pair camera-" + spec.cameraId());
        }
    }

    public void postBankState(String state, String label) {
        postJson(flashBankUrl, Map.of("state", state), label);
    }

    public void postJson(String url, Map<String, Object> body, String label) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LightServerV3Http.requireLightCommandSuccess("light", "POST", url, response);
            if (response.body() != null && !response.body().isBlank()) {
                LOG.info("light {} -> {}", label, response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new LightingException(label + " failed: " + formatError(e), e);
        }
    }

    public boolean pollBankInitialized() throws LightingException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(statusUrl))
                    .timeout(statusPollTimeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2 || response.body() == null || response.body().isBlank()) {
                return false;
            }
            JsonNode root = MAPPER.readTree(response.body());
            if (root.path("initialized").asBoolean(false)) {
                return true;
            }
            return root.path("ready").asInt(0) > 0;
        } catch (LightingException e) {
            throw e;
        } catch (Exception e) {
            throw new LightingException("pollBankInitialized failed: " + formatError(e), e);
        }
    }

    public static String formatError(Throwable error) {
        String message = error.getMessage();
        return message != null && !message.isBlank() ? message : error.getClass().getSimpleName();
    }

    public String onUrl() {
        return onUrl;
    }

    public String offUrl() {
        return offUrl;
    }

    public String brightnessPairUrl() {
        return brightnessPairUrl;
    }

    public String brightnessSingleUrl() {
        return brightnessSingleUrl;
    }

    public String flashBankUrl() {
        return flashBankUrl;
    }

    public Duration timeout() {
        return timeout;
    }
}
