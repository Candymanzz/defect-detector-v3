package com.example.iml.orchestrator.integration.lighting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LightServer.v3 ComLight (Swagger): {@code POST /api/com/light?ports=COMx} с телом
 * {@code { comPort, lightControllerSource, channels, brightness }}.
 * Опционально {@code GET /api/com/devices?ports=...} — не вызываем перед вспышкой (долгое MVS enum).
 * При {@code IoController:FlashMode=Hold} IO box держит свет до {@code Off}; Trigger — только короткий импульс.
 */
public final class ComIoLightEndpoint implements LightEndpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger log;
    private final String id;
    private final boolean enabled;
    private final URI lightUri;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final String comPort;
    private final int[] channels;
    private final int[] brightnessRaw;
    private final int[] cameraIds;

    public ComIoLightEndpoint(
            Logger log,
            String id,
            boolean enabled,
            String baseUrl,
            String comPort,
            String comPortsQuery,
            int timeoutMs,
            int[] channels,
            int[] brightnessRaw,
            int[] cameraIds
    ) {
        this.log = log;
        this.id = id;
        this.enabled = enabled;
        String base = LightServerV3Http.normalizeBaseUrl(baseUrl);
        String portsQuery = buildPortsQuery(comPort, comPortsQuery);
        this.lightUri = URI.create(base + LightServerV3Http.PATH_COM_LIGHT + portsQuery);
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.comPort = comPort == null || comPort.isBlank() ? "COM1" : comPort.trim();
        this.channels = channels == null || channels.length == 0 ? new int[]{1, 2, 3, 4} : channels.clone();
        this.brightnessRaw = brightnessRaw == null ? null : brightnessRaw.clone();
        this.cameraIds = cameraIds == null ? new int[0] : cameraIds.clone();
    }

    private static String buildPortsQuery(String comPort, String comPortsQuery) {
        String ports = comPortsQuery != null && !comPortsQuery.isBlank()
                ? comPortsQuery.trim()
                : comPort;
        if (ports == null || ports.isBlank()) {
            return "";
        }
        return "?ports=" + URLEncoder.encode(ports, StandardCharsets.UTF_8);
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
     * держит {@code _sdkLock} в LightServer и мешает POST /api/com/light (IoCom fallback).
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
        int[] activeChannels = channelsForCamera(cameraId);
        if (activeChannels == null || activeChannels.length == 0) {
            return;
        }
        int[] brightness = resolveBrightness(brightnessPercent, activeChannels);
        postLight("On", activeChannels, brightness);
        log.info("light {} COM On cam={} frame={} phase={} port={} brightness={}",
                id, cameraId, frameId, phase, comPort, brightness);
    }

    @Override
    public void turnOffAll() throws Exception {
        if (!enabled) {
            return;
        }
        postLight("Off", channels, null);
        log.info("light {} COM Off port={}", id, comPort);
    }

    public void turnOffForCamera(int cameraId) throws Exception {
        if (!enabled) {
            return;
        }
        int[] activeChannels = channelsForCamera(cameraId);
        if (activeChannels == null || activeChannels.length == 0) {
            return;
        }
        postLight("Off", activeChannels, null);
        log.info("light {} COM Off cam={} port={}", id, cameraId, comPort);
    }

    private void postLight(String source, int[] activeChannels, int[] brightness) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("comPort", comPort);
        body.put("lightControllerSource", source);
        body.put("channels", activeChannels);
        if (brightness != null) {
            body.put("brightness", brightness);
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
            log.info("light {} COM {} port={} -> {}", id, source, comPort, response.body());
        }
    }

    private int[] channelsForCamera(int cameraId) {
        if (cameraIds.length == 0) {
            return channels;
        }
        for (int i = 0; i < cameraIds.length; i++) {
            if (cameraIds[i] == cameraId) {
                if (channels.length == cameraIds.length) {
                    return new int[]{channels[i]};
                }
                return channels;
            }
        }
        return null;
    }

    private int[] resolveBrightness(int brightnessPercent, int[] activeChannels) {
        if (brightnessRaw != null && brightnessRaw.length > 0) {
            if (brightnessRaw.length >= activeChannels.length) {
                int[] out = new int[activeChannels.length];
                for (int i = 0; i < activeChannels.length; i++) {
                    out[i] = brightnessRaw[i];
                }
                return out;
            }
            int[] out = new int[activeChannels.length];
            for (int i = 0; i < activeChannels.length; i++) {
                out[i] = brightnessRaw[Math.min(i, brightnessRaw.length - 1)];
            }
            return out;
        }
        return LightBrightnessScale.mvLeBrightnessForChannels(brightnessPercent, activeChannels);
    }
}
