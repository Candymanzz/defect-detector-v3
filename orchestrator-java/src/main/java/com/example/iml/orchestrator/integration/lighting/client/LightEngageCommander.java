package com.example.iml.orchestrator.integration.lighting.client;

import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import com.example.iml.orchestrator.integration.lighting.LightingException;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Engage / bank retries / brightness push helpers.
 * Caller owns {@code lightCommandLock} for locked methods.
 */
public final class LightEngageCommander {

    private static final int MAX_TRIGGER_ATTEMPTS = 10;
    private static final long RETRY_DELAY_MS = 200L;

    private final Logger log;
    private final LightServerHttpTransport transport;
    private final LightBrightnessMemory brightnessMemory;
    private final boolean enabled;
    private final boolean failOnError;
    private final int settleDelayMs;

    public LightEngageCommander(
            Logger log,
            LightServerHttpTransport transport,
            LightBrightnessMemory brightnessMemory,
            boolean enabled,
            boolean failOnError,
            int settleDelayMs
    ) {
        this.log = log;
        this.transport = transport;
        this.brightnessMemory = brightnessMemory;
        this.enabled = enabled;
        this.failOnError = failOnError;
        this.settleDelayMs = settleDelayMs;
    }

    public boolean engageLightingLocked() {
        if (brightnessMemory.hasCameras()) {
            return applyAllCameraBrightnessLocked();
        }
        return postOnWithRetriesLocked(brightnessMemory.brightnessPercent());
    }

    public boolean engageCameraLightingLocked(int cameraId) {
        LightServersConfig.CameraFlashSpec spec = brightnessMemory.camera(cameraId);
        if (spec != null) {
            try {
                transport.pushCameraBrightness(spec);
                sleepSettle();
                return true;
            } catch (LightingException e) {
                if (failOnError) {
                    throw new LightingException("light camera-" + cameraId + " on failed", e);
                }
                log.warn("light camera-{} on failed: {}", cameraId, LightServerHttpTransport.formatError(e));
                return false;
            }
        }
        if (brightnessMemory.hasCameras()) {
            log.warn("light camera-{} not configured in light_servers.cameras", cameraId);
            return false;
        }
        return postOnWithRetriesLocked(brightnessMemory.brightnessPercent());
    }

    public List<String> pushCameraBrightnessIfEnabled(List<LightServersConfig.CameraFlashSpec> specs) {
        if (!enabled || specs == null || specs.isEmpty()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        for (LightServersConfig.CameraFlashSpec spec : specs) {
            errors.addAll(pushCameraBrightnessIfEnabled(spec));
        }
        return errors;
    }

    public List<String> pushCameraBrightnessIfEnabled(LightServersConfig.CameraFlashSpec spec) {
        if (!enabled) {
            return List.of();
        }
        try {
            transport.pushCameraBrightness(spec);
            return List.of();
        } catch (LightingException e) {
            String message = LightServerHttpTransport.formatError(e);
            log.warn("light camera-{} brightness push failed: {}", spec.cameraId(), message);
            return List.of("camera-" + spec.cameraId() + ": " + message);
        }
    }

    public boolean applyAllCameraBrightnessLocked() {
        List<String> errors = new ArrayList<>();
        for (LightServersConfig.CameraFlashSpec spec : brightnessMemory.snapshot()) {
            try {
                transport.pushCameraBrightness(spec);
            } catch (LightingException e) {
                errors.add("camera-" + spec.cameraId() + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            if (failOnError) {
                throw new LightingException("light brightness apply failed: " + String.join("; ", errors));
            }
            log.warn("light brightness apply partial failure: {}", String.join("; ", errors));
            return false;
        }
        return true;
    }

    public boolean postOnWithRetriesLocked(int brightnessPercent) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_TRIGGER_ATTEMPTS; attempt++) {
            try {
                transport.postOn(brightnessPercent);
                sleepSettle();
                return true;
            } catch (RuntimeException e) {
                last = e;
                log.warn("light On attempt {}/{} failed: {}", attempt, MAX_TRIGGER_ATTEMPTS, e.getMessage());
                if (attempt < MAX_TRIGGER_ATTEMPTS) {
                    sleepRetryDelay();
                }
            }
        }
        if (failOnError && last != null) {
            throw last;
        }
        return false;
    }

    public void postOffWithRetriesLocked() {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_TRIGGER_ATTEMPTS; attempt++) {
            try {
                transport.postOff();
                return;
            } catch (RuntimeException e) {
                last = e;
                log.warn("light Off attempt {}/{} failed: {}", attempt, MAX_TRIGGER_ATTEMPTS, e.getMessage());
                if (attempt < MAX_TRIGGER_ATTEMPTS) {
                    sleepRetryDelay();
                }
            }
        }
        if (failOnError && last != null) {
            throw last;
        }
    }

    public void sleepSettle() {
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
