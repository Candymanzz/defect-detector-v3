package com.example.iml.orchestrator.integration.lighting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * LightServer.v3 ComLightApply: {@code POST /api/com/light} с телом
 * {@code { "state": "On"|"Off", "brightness": "100" }} — яркость в % через запятую по всем каналам банка
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
    private final Duration statusPollTimeout;
    private final String comPort;
    private final int[] channels;
    private final int[] defaultBrightnessRaw;
    private volatile boolean bankReadyLogged;
    private volatile List<BankChannel> bankLayout = List.of();

    public ComIoLightEndpoint(
            Logger log,
            String id,
            boolean enabled,
            String baseUrl,
            int timeoutMs,
            String comPort,
            int[] channels,
            int[] defaultBrightnessRaw
    ) {
        this.log = log;
        this.id = id;
        this.enabled = enabled;
        String base = LightServerV3Http.normalizeBaseUrl(baseUrl);
        this.lightUri = URI.create(base + LightServerV3Http.PATH_COM_LIGHT);
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.statusPollTimeout = Duration.ofMillis(Math.min(3000, Math.max(500, timeoutMs / 5)));
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.comPort = normalizeComPort(comPort);
        this.channels = channels == null || channels.length == 0 ? new int[]{1, 2, 3, 4} : channels.clone();
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
     * Ждём {@code GET /api/com/light} → {@code initialized: true} (банк COM из appsettings LightServer).
     * Не вызываем {@code GET /api/com/devices} — долгое MVS enum.
     */
    @Override
    public void ensureReady() {
        if (!enabled) {
            return;
        }
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            try {
                if (pollBankInitialized()) {
                    return;
                }
            } catch (Exception e) {
                log.debug("light {} COM bank status poll: {}", id, e.getMessage());
            }
            try {
                Thread.sleep(400L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("light {} COM bank not initialized within {} ms — первый POST on может занять 8–12 s",
                id, timeout.toMillis());
    }

    private boolean pollBankInitialized() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(lightUri)
                .timeout(statusPollTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2 || response.body() == null || response.body().isBlank()) {
            return false;
        }
        JsonNode root = MAPPER.readTree(response.body());
        bankLayout = parseBankLayout(root.path("devices"));
        if (!root.path("initialized").asBoolean(false)) {
            return false;
        }
        if (!bankReadyLogged) {
            bankReadyLogged = true;
            log.info("light {} COM bank ready (initialized), devices={}", id, root.path("devices"));
        }
        return true;
    }

    @Override
    public void trigger(int cameraId, long frameId, String phase, int brightnessPercent, int durationMs) throws Exception {
        if (!enabled) {
            return;
        }
        String brightness = formatBrightnessCsv(brightnessPercent, defaultBrightnessRaw, bankLayout, comPort, channels);
        postState("On", brightness);
        log.info("light {} COM bank on cam={} frame={} phase={} brightness={}",
                id, cameraId, frameId, phase, brightness);
    }

    @Override
    public void turnOffAll() throws Exception {
        if (!enabled) {
            return;
        }
        postState("Off", null);
        log.info("light {} COM bank off", id);
    }

    @Override
    public void turnOffForCamera(int cameraId) throws Exception {
        if (!enabled) {
            return;
        }
        postState("Off", null);
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
        return formatBrightnessCsv(brightnessPercent, brightnessRaw, List.of(), "", new int[0]);
    }

    static String formatBrightnessCsv(
            int brightnessPercent,
            int[] brightnessRaw,
            List<BankChannel> layout,
            String comPort,
            int[] channels
    ) {
        int percent = LightBrightnessScale.clampPercent(brightnessPercent);
        if (layout == null || layout.isEmpty() || comPort == null || comPort.isBlank() || channels == null || channels.length == 0) {
            if (brightnessRaw == null || brightnessRaw.length == 0) {
                return Integer.toString(percent);
            }
            return IntStream.of(brightnessRaw)
                    .mapToObj(v -> Integer.toString(LightBrightnessScale.toPercent(v, percent)))
                    .collect(Collectors.joining(","));
        }

        int[] out = new int[layout.size()];
        int sourceIndex = 0;
        for (int i = 0; i < layout.size(); i++) {
            BankChannel bankChannel = layout.get(i);
            if (!bankChannel.matches(comPort, channels)) {
                continue;
            }
            int raw = brightnessRaw != null && brightnessRaw.length > 0
                    ? brightnessRaw[Math.min(sourceIndex, brightnessRaw.length - 1)]
                    : percent;
            out[i] = LightBrightnessScale.toPercent(raw, percent);
            sourceIndex++;
        }

        return IntStream.of(out)
                .mapToObj(Integer::toString)
                .collect(Collectors.joining(","));
    }

    private static List<BankChannel> parseBankLayout(JsonNode devices) {
        if (devices == null || !devices.isArray()) {
            return List.of();
        }
        List<BankChannel> out = new ArrayList<>();
        for (JsonNode device : devices) {
            String port = normalizeComPort(device.path("ComPort").asText(device.path("comPort").asText("")));
            JsonNode channels = device.path("Channels");
            if (channels.isMissingNode()) {
                channels = device.path("channels");
            }
            if (!channels.isArray()) {
                continue;
            }
            for (JsonNode channel : channels) {
                out.add(new BankChannel(port, channel.asInt()));
            }
        }
        return List.copyOf(out);
    }

    private static String normalizeComPort(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    record BankChannel(String comPort, int channel) {
        boolean matches(String targetComPort, int[] targetChannels) {
            if (!comPort.equals(normalizeComPort(targetComPort))) {
                return false;
            }
            for (int targetChannel : targetChannels) {
                if (channel == targetChannel) {
                    return true;
                }
            }
            return false;
        }
    }
}
