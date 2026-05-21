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
 * LightServer.v3 (IO Box по COM): {@code POST /api/com/light} с {@code lightControllerSource=On}.
 * В appsettings IoController:FlashMode=Trigger сервер выполняет импульс вспышки.
 */
public final class ComIoLightEndpoint implements LightEndpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Logger log;
    private final String id;
    private final boolean enabled;
    private final URI lightUri;
    private final URI devicesUri;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final String comPort;
    private final int[] channels;
    private volatile boolean readyChecked;

    public ComIoLightEndpoint(
            Logger log,
            String id,
            boolean enabled,
            String baseUrl,
            String comPort,
            String comPortsQuery,
            int timeoutMs,
            int[] channels
    ) {
        this.log = log;
        this.id = id;
        this.enabled = enabled;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String portsQuery = buildPortsQuery(comPort, comPortsQuery);
        this.lightUri = URI.create(base + "/api/com/light" + portsQuery);
        this.devicesUri = URI.create(base + "/api/com/devices" + portsQuery);
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.comPort = comPort == null || comPort.isBlank() ? "COM1" : comPort.trim();
        this.channels = channels == null || channels.length == 0 ? new int[]{1, 2, 3, 4} : channels.clone();
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
                        .uri(devicesUri)
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
            log.warn("light endpoint {} COM devices not ready at {}", id, devicesUri);
            readyChecked = true;
        }
    }

    @Override
    public void trigger(int cameraId, long frameId, String phase, int brightnessPercent, int durationMs) throws Exception {
        if (!enabled) {
            return;
        }
        ensureReady();
        postLight("On", LightBrightnessScale.mvLeBrightnessForChannels(brightnessPercent, channels));
        log.info("light {} COM flash cam={} frame={} phase={} port={} brightness%={}",
                id, cameraId, frameId, phase, comPort, brightnessPercent);
    }

    @Override
    public void turnOffAll() throws Exception {
        if (!enabled) {
            return;
        }
        postLight("Off", null);
        log.info("light {} COM Off port={} (shutdown)", id, comPort);
    }

    private void postLight(String source, int[] brightness) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("comPort", comPort);
        body.put("lightControllerSource", source);
        body.put("channels", channels);
        if (brightness != null) {
            body.put("brightness", brightness);
        }
        byte[] json = MAPPER.writeValueAsBytes(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(lightUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(id + " POST /api/com/light failed status=" + response.statusCode()
                    + " body=" + response.body());
        }
    }
}
