package com.example.iml.orchestrator.integration.lighting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP-триггер вспышек LightServer.v3: три типа URL — вкл, выкл, яркость ({@code /api/camera-flash/pair|single}).
 */
public final class LightTriggerClient {

    private static final Logger LOG = LogManager.getLogger(LightTriggerClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TRIGGER_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MS = 200L;

    private final boolean enabled;
    private final boolean failOnError;
    private final int defaultBrightnessPercent;
    private final boolean holdMode;
    private final int timeoutMs;
    private final int settleDelayMs;
    private final String onUrl;
    private final String offUrl;
    private final String brightnessPairUrl;
    private final String brightnessSingleUrl;
    private final String statusUrl;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final Duration statusPollTimeout;
    private final List<LightServersConfig.CameraFlashSpec> cameras;
    private final Map<Integer, LightServersConfig.CameraFlashSpec> cameraById;
    private volatile boolean constantLightingEngaged;
    private final Object lightCommandLock;

    public static LightTriggerClient fromRootYaml(Map<String, Object> root) {
        return new LightTriggerClient(LightServersConfig.fromRootYaml(root));
    }

    public LightTriggerClient(LightServersConfig cfg) {
        this.enabled = cfg.enabled();
        this.failOnError = cfg.failOnError();
        this.defaultBrightnessPercent = cfg.brightnessPercent();
        this.holdMode = cfg.holdMode();
        this.timeoutMs = Math.max(100, cfg.timeoutMs());
        this.settleDelayMs = Math.max(0, cfg.settleDelayMs());
        this.onUrl = cfg.onUrl();
        this.offUrl = cfg.offUrl();
        this.brightnessPairUrl = cfg.brightnessPairUrl();
        this.brightnessSingleUrl = cfg.brightnessSingleUrl();
        this.statusUrl = cfg.statusUrl();
        this.timeout = Duration.ofMillis(this.timeoutMs);
        this.statusPollTimeout = Duration.ofMillis(Math.min(3000, Math.max(500, this.timeoutMs / 5)));
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.cameras = new ArrayList<>(cfg.cameras());
        this.cameraById = indexCameras(this.cameras);
        this.constantLightingEngaged = false;
        this.lightCommandLock = new Object();
        if (enabled) {
            LOG.info(
                    "light_servers: on={} off={} brightness_pair={} brightness_single={} cameras={} default_brightness_percent={} hold_mode={}",
                    onUrl, offUrl, brightnessPairUrl, brightnessSingleUrl, cameras.size(), defaultBrightnessPercent, holdMode
            );
            for (LightServersConfig.CameraFlashSpec c : cameras) {
                LOG.info("  light camera id={} mode={} brightness_percent={}", c.cameraId(), c.mode(), c.brightnessPercent());
            }
        }
    }

    private static Map<Integer, LightServersConfig.CameraFlashSpec> indexCameras(
            List<LightServersConfig.CameraFlashSpec> cameras
    ) {
        Map<Integer, LightServersConfig.CameraFlashSpec> out = new LinkedHashMap<>();
        for (LightServersConfig.CameraFlashSpec c : cameras) {
            out.put(c.cameraId(), c);
        }
        return out;
    }

    public int brightnessPercent() {
        return defaultBrightnessPercent;
    }

    public int brightnessPercent(String endpointId) {
        Integer cameraId = parseCameraIdFromEndpoint(endpointId);
        if (cameraId == null) {
            return defaultBrightnessPercent;
        }
        LightServersConfig.CameraFlashSpec spec = cameraById.get(cameraId);
        return spec == null ? defaultBrightnessPercent : spec.brightnessPercent();
    }

    public Map<String, Integer> brightnessByEndpoint() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (LightServersConfig.CameraFlashSpec c : cameras) {
            out.put(c.endpointId(), c.brightnessPercent());
        }
        return Map.copyOf(out);
    }

    public List<String> endpointIds() {
        return cameras.stream().map(LightServersConfig.CameraFlashSpec::endpointId).toList();
    }

    public int[] cameraIds(String endpointId) {
        Integer cameraId = parseCameraIdFromEndpoint(endpointId);
        return cameraId == null ? new int[0] : new int[]{cameraId};
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isHoldMode() {
        return holdMode;
    }

    public void engageConstantLighting() {
        if (!enabled || !holdMode) {
            return;
        }
        synchronized (lightCommandLock) {
            if (constantLightingEngaged) {
                return;
            }
            LOG.info("light hold_mode: яркость по камерам → POST on");
            if (!applyAllCameraBrightnessLocked() || !postOnLocked(defaultBrightnessPercent)) {
                LOG.warn("light hold_mode: не удалось включить подсветку при старте");
                return;
            }
            constantLightingEngaged = true;
            sleepSettle();
        }
    }

    /** Дождаться инициализации COM-банка в LightServer ({@code GET status_url}). */
    public void awaitEndpointsReady() {
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
                LOG.debug("light COM bank status poll: {}", e.getMessage());
            }
            try {
                Thread.sleep(400L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.warn("light COM bank not initialized within {} ms — первый POST on может занять 8–12 s", timeout.toMillis());
    }

    public void setBrightnessPercent(int percent) {
        int clamped = LightBrightnessScale.clampPercent(percent);
        List<Integer> ids = cameras.stream().map(LightServersConfig.CameraFlashSpec::cameraId).toList();
        synchronized (lightCommandLock) {
            for (int cameraId : ids) {
                updateCameraBrightness(cameraId, clamped, clamped, clamped);
            }
        }
    }

    public void setBrightnessPercent(String endpointId, int percent) {
        Integer cameraId = parseCameraIdFromEndpoint(endpointId);
        if (cameraId == null) {
            throw new IllegalArgumentException("unknown light endpoint id: " + endpointId);
        }
        int clamped = LightBrightnessScale.clampPercent(percent);
        synchronized (lightCommandLock) {
            updateCameraBrightness(cameraId, clamped, clamped, clamped);
        }
    }

    public void trigger(int cameraId, long frameId, String phase) {
        lightOn(cameraId, frameId, phase);
    }

    public void runCaptureWithLighting(
            int cameraId,
            long frameId,
            String phase,
            int flashLeadMs,
            CaptureStep captureStep
    ) throws Exception {
        if (!enabled) {
            captureStep.run();
            return;
        }
        if (holdMode) {
            if (!constantLightingEngaged) {
                engageConstantLighting();
            }
            captureStep.run();
            return;
        }
        synchronized (lightCommandLock) {
            if (!postOnWithRetriesLocked(defaultBrightnessPercent)) {
                LOG.warn("light On failed cam={} phase={} — skip Off and capture without lighting", cameraId, phase);
                captureStep.run();
                return;
            }
            if (flashLeadMs > 0) {
                Thread.sleep(flashLeadMs);
            }
            try {
                captureStep.run();
            } finally {
                postOffWithRetriesLocked();
                sleepSettle();
            }
        }
    }

    @FunctionalInterface
    public interface CaptureStep {
        void run() throws Exception;
    }

    public boolean lightOn(int cameraId, long frameId, String phase) {
        if (!enabled) {
            return false;
        }
        LOG.info("light On cam={} frame={} phase={} brightness={}", cameraId, frameId, phase, brightnessByEndpoint());
        synchronized (lightCommandLock) {
            return postOnWithRetriesLocked(defaultBrightnessPercent);
        }
    }

    public void lightOff(int cameraId, long frameId, String phase) {
        if (!enabled) {
            return;
        }
        LOG.info("light Off cam={} frame={} phase={}", cameraId, frameId, phase);
        synchronized (lightCommandLock) {
            postOffWithRetriesLocked();
        }
    }

    public void forceAllOff() {
        if (!enabled) {
            return;
        }
        synchronized (lightCommandLock) {
            postOffWithRetriesLocked();
        }
    }

    public void shutdown() {
        forceAllOff();
    }

    private void updateCameraBrightness(int cameraId, int percent, int leftPercent, int rightPercent) {
        LightServersConfig.CameraFlashSpec existing = cameraById.get(cameraId);
        if (existing == null) {
            throw new IllegalArgumentException("unknown camera id: " + cameraId);
        }
        int before = existing.brightnessPercent();
        LightServersConfig.CameraFlashSpec updated = new LightServersConfig.CameraFlashSpec(
                cameraId, existing.mode(), percent, leftPercent, rightPercent
        );
        cameraById.put(cameraId, updated);
        for (int i = 0; i < cameras.size(); i++) {
            if (cameras.get(i).cameraId() == cameraId) {
                cameras.set(i, updated);
                break;
            }
        }
        if (before != percent) {
            LOG.info("light camera-{} brightness {}% -> {}%", cameraId, before, percent);
        }
        try {
            pushCameraBrightness(updated);
        } catch (Exception e) {
            LOG.warn("light camera-{} brightness push failed: {}", cameraId, e.getMessage());
        }
    }

    private boolean applyAllCameraBrightnessLocked() {
        List<String> errors = new ArrayList<>();
        for (LightServersConfig.CameraFlashSpec spec : cameras) {
            try {
                pushCameraBrightness(spec);
            } catch (Exception e) {
                errors.add("camera-" + spec.cameraId() + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            if (failOnError) {
                throw new IllegalStateException("light brightness apply failed: " + String.join("; ", errors));
            }
            LOG.warn("light brightness apply partial failure: {}", String.join("; ", errors));
            return false;
        }
        return true;
    }

    private boolean postOnWithRetriesLocked(int brightnessPercent) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_TRIGGER_ATTEMPTS; attempt++) {
            try {
                postOn(brightnessPercent);
                sleepSettle();
                return true;
            } catch (RuntimeException e) {
                lastError = e;
            }
            if (attempt < MAX_TRIGGER_ATTEMPTS) {
                sleepRetryDelay();
            }
        }
        if (lastError == null) {
            return false;
        }
        if (failOnError) {
            throw lastError;
        }
        LOG.warn("light On failed: {}", lastError.getMessage());
        return false;
    }

    private void postOffWithRetriesLocked() {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_TRIGGER_ATTEMPTS; attempt++) {
            try {
                postOff();
                return;
            } catch (RuntimeException e) {
                lastError = e;
            }
            if (attempt < MAX_TRIGGER_ATTEMPTS) {
                sleepRetryDelay();
            }
        }
        if (lastError != null) {
            if (failOnError) {
                throw lastError;
            }
            LOG.warn("light Off failed: {}", lastError.getMessage());
        }
    }

    private boolean postOnLocked(int brightnessPercent) {
        try {
            postOn(brightnessPercent);
            sleepSettle();
            return true;
        } catch (RuntimeException e) {
            if (failOnError) {
                throw e;
            }
            LOG.warn("light On failed: {}", e.getMessage());
            return false;
        }
    }

    private void postOn(int brightnessPercent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", "on");
        body.put("brightness", Integer.toString(LightBrightnessScale.clampPercent(brightnessPercent)));
        postJson(onUrl, body, "On");
    }

    private void postOff() {
        Map<String, Object> body = Map.of("state", "off");
        postJson(offUrl, body, "Off");
    }

    private void pushCameraBrightness(LightServersConfig.CameraFlashSpec spec) throws Exception {
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

    private void postJson(String url, Map<String, Object> body, String label) {
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
            throw new IllegalStateException(label + " failed: " + e.getMessage(), e);
        }
    }

    private boolean pollBankInitialized() throws Exception {
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
        return root.path("initialized").asBoolean(false);
    }

    private static Integer parseCameraIdFromEndpoint(String endpointId) {
        if (endpointId == null || endpointId.isBlank()) {
            return null;
        }
        if (endpointId.startsWith("camera-") || endpointId.startsWith("camera_")) {
            try {
                return Integer.parseInt(endpointId.substring(endpointId.indexOf('-') >= 0
                        ? endpointId.indexOf('-') + 1
                        : endpointId.indexOf('_') + 1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (endpointId.startsWith("cam-") || endpointId.startsWith("cam_")) {
            try {
                return Integer.parseInt(endpointId.substring(4));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        try {
            return Integer.parseInt(endpointId);
        } catch (NumberFormatException ignored) {
            return null;
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

    private void sleepRetryDelay() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}


