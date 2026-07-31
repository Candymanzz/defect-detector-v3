package com.example.iml.orchestrator.integration.lighting.client;

import com.example.iml.orchestrator.integration.lighting.LightingException;

import com.example.iml.orchestrator.integration.pipeline.spi.CaptureLightingPort;
import org.apache.logging.log4j.Logger;

import java.util.function.BooleanSupplier;

/**
 * Per-capture On → flash lead → capture → Off session.
 * Synchronizes on the shared {@code lightCommandLock} for the flash path.
 */
public final class CaptureLightingSession {

    private final Logger log;
    private final LightEngageCommander engage;
    private final Object lightCommandLock;
    private final boolean enabled;
    private final boolean holdMode;
    private final BooleanSupplier constantLightingEngaged;

    public CaptureLightingSession(
            Logger log,
            LightEngageCommander engage,
            Object lightCommandLock,
            boolean enabled,
            boolean holdMode,
            BooleanSupplier constantLightingEngaged
    ) {
        this.log = log;
        this.engage = engage;
        this.lightCommandLock = lightCommandLock;
        this.enabled = enabled;
        this.holdMode = holdMode;
        this.constantLightingEngaged = constantLightingEngaged;
    }

    public void run(
            int cameraId,
            String phase,
            int flashLeadMs,
            CaptureLightingPort.CaptureStep captureStep
    ) throws LightingException {
        if (!enabled) {
            captureStep.run();
            return;
        }
        if (holdMode) {
            // Подсветка включается один раз при старте (IntegrationBootstrap.startupEngage).
            // Повторная инициализация здесь блокировала потоки camera-flow на HTTP к LightServer
            // и разрушала барьер line synchronized capture (камеры приходили в разное время).
            if (!constantLightingEngaged.getAsBoolean()) {
                log.warn(
                        "light hold_mode: подсветка не была включена при старте — capture без блокирующей инициализации cam={}",
                        cameraId
                );
            }
            captureStep.run();
            return;
        }
        synchronized (lightCommandLock) {
            if (!engage.engageCameraLightingLocked(cameraId)) {
                log.warn("light On failed cam={} phase={} — skip Off and capture without lighting", cameraId, phase);
                captureStep.run();
                return;
            }
            if (flashLeadMs > 0) {
                try {
                    Thread.sleep(flashLeadMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LightingException("light flash lead interrupted cam=" + cameraId, e);
                }
            }
            try {
                captureStep.run();
            } finally {
                engage.postOffWithRetriesLocked();
                engage.sleepSettle();
            }
        }
    }
}
