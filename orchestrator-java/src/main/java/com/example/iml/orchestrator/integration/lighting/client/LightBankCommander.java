package com.example.iml.orchestrator.integration.lighting.client;

import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Bank On/Off and force-all-off wrappers.
 * Synchronizes on the shared {@code lightCommandLock}.
 */
public final class LightBankCommander {

    private final Logger log;
    private final LightServerHttpTransport transport;
    private final LightEngageCommander engage;
    private final LightBrightnessMemory brightnessMemory;
    private final Object lightCommandLock;
    private final boolean enabled;
    private final boolean failOnError;
    private final Supplier<Map<String, Integer>> brightnessByEndpoint;

    public LightBankCommander(
            Logger log,
            LightServerHttpTransport transport,
            LightEngageCommander engage,
            LightBrightnessMemory brightnessMemory,
            Object lightCommandLock,
            boolean enabled,
            boolean failOnError,
            Supplier<Map<String, Integer>> brightnessByEndpoint
    ) {
        this.log = log;
        this.transport = transport;
        this.engage = engage;
        this.brightnessMemory = brightnessMemory;
        this.lightCommandLock = lightCommandLock;
        this.enabled = enabled;
        this.failOnError = failOnError;
        this.brightnessByEndpoint = brightnessByEndpoint;
    }

    public boolean lightOn(int cameraId, long frameId, String phase) {
        if (!enabled) {
            return false;
        }
        log.info("light On cam={} frame={} phase={} brightness={}",
                cameraId, frameId, phase, brightnessByEndpoint.get());
        synchronized (lightCommandLock) {
            return engage.engageCameraLightingLocked(cameraId);
        }
    }

    /**
     * Параллельный On всех устройств через {@code POST /api/camera-flash/bank} (Ethernet + COM).
     * Не трогает capture pipeline.
     */
    public boolean bankAllOn(String phase) {
        if (!enabled) {
            return false;
        }
        log.info("light bank On phase={} brightness={}", phase, brightnessByEndpoint.get());
        synchronized (lightCommandLock) {
            try {
                transport.postBankState("on", "bank-On");
                return true;
            } catch (RuntimeException e) {
                if (failOnError) {
                    throw e;
                }
                log.warn("light bank On failed: {}", e.getMessage());
                return false;
            }
        }
    }

    /**
     * Параллельный Off всех устройств (Ethernet + COM) через bank API.
     */
    public void bankAllOff() {
        if (!enabled) {
            return;
        }
        synchronized (lightCommandLock) {
            try {
                transport.postBankState("off", "bank-Off");
            } catch (RuntimeException e) {
                if (failOnError) {
                    throw e;
                }
                log.warn("light bank Off failed: {}", e.getMessage());
            }
        }
    }

    public void forceAllOff() {
        if (!enabled) {
            return;
        }
        // При interval_flash / полном железе (Ethernet+COM) гасим через bank.
        // Legacy COM-only fallback: /api/com/light.
        if (brightnessMemory.hasCameras()) {
            bankAllOff();
            return;
        }
        synchronized (lightCommandLock) {
            engage.postOffWithRetriesLocked();
        }
    }
}
