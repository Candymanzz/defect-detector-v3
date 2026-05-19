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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * LightServerv.v2 (MV-LE): {@code POST /api/light} On/Off, яркость 0…255 (из единых {@code 0…100}%).
 */
public final class MvLeLightEndpoint implements LightEndpoint {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ScheduledExecutorService OFF_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mv-le-light-off");
        t.setDaemon(true);
        return t;
    });

    private final Logger log;
    private final String id;
    private final boolean enabled;
    private final URI lightUri;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final int deviceIndex;
    private final int[] channels;

    public MvLeLightEndpoint(
            Logger log,
            String id,
            boolean enabled,
            String baseUrl,
            int timeoutMs,
            int deviceIndex,
            int[] channels
    ) {
        this.log = log;
        this.id = id;
        this.enabled = enabled;
        this.lightUri = URI.create(baseUrl + "/api/light");
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.deviceIndex = deviceIndex;
        this.channels = channels == null || channels.length == 0 ? new int[]{1, 2, 3, 4} : channels.clone();
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
        // MV-LE API не имеет /status; готовность проверяется при первом POST.
    }

    @Override
    public void turnOffAll() throws Exception {
        if (!enabled) {
            return;
        }
        postLight("Off", null);
        log.info("light {} MV-LE Off (shutdown)", id);
    }

    /** Остановить отложенные Off после завершения оркестратора. */
    public static void shutdownScheduler() {
        OFF_SCHEDULER.shutdownNow();
    }

    @Override
    public void trigger(int cameraId, long frameId, String phase, int brightnessPercent, int durationMs) throws Exception {
        if (!enabled) {
            return;
        }
        int[] brightness = LightBrightnessScale.mvLeBrightnessForChannels(brightnessPercent, channels);
        postLight("On", brightness);
        log.info("light {} MV-LE On cam={} frame={} phase={} brightness%={} mvLe={}",
                id, cameraId, frameId, phase, brightnessPercent, brightness[0]);
        int offDelay = Math.max(1, durationMs);
        OFF_SCHEDULER.schedule(() -> {
            try {
                postLight("Off", null);
                log.debug("light {} Off after {}ms", id, offDelay);
            } catch (Exception e) {
                log.warn("light {} Off failed: {}", id, e.getMessage());
            }
        }, offDelay, TimeUnit.MILLISECONDS);
    }

    private void postLight(String source, int[] brightness) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceIndex", deviceIndex);
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
            throw new IllegalStateException(id + " POST /api/light failed status=" + response.statusCode()
                    + " body=" + response.body());
        }
    }
}
