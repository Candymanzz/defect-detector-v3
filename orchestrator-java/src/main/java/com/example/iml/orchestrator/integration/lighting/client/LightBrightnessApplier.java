package com.example.iml.orchestrator.integration.lighting.client;

import com.example.iml.orchestrator.integration.lighting.LightBrightnessApplyResult;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessScale;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessUpdate;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Brightness memory updates, hardware push, capture-window defer, and flush.
 * Synchronizes on the shared {@code lightCommandLock} where memory/bank state is touched.
 */
public final class LightBrightnessApplier {

    private final Logger log;
    private final LightEngageCommander engage;
    private final LightBrightnessMemory brightnessMemory;
    private final LightServerHttpTransport transport;
    private final Object lightCommandLock;
    private final boolean enabled;
    private final BooleanSupplier constantFlashMode;
    private final Runnable markConstantLightingEngaged;

    /** После HTTP яркости (legacy hook). Interval flash больше не делает Off→On→Off. */
    private volatile Runnable afterBrightnessApplied;
    /** true в окне DI3→кадр→Off: hardware push яркости откладывается. */
    private volatile BooleanSupplier captureLightingActive;
    private volatile boolean deferredHardwareBrightness;

    public LightBrightnessApplier(
            Logger log,
            LightEngageCommander engage,
            LightBrightnessMemory brightnessMemory,
            LightServerHttpTransport transport,
            Object lightCommandLock,
            boolean enabled,
            BooleanSupplier constantFlashMode,
            Runnable markConstantLightingEngaged
    ) {
        this.log = log;
        this.engage = engage;
        this.brightnessMemory = brightnessMemory;
        this.transport = transport;
        this.lightCommandLock = lightCommandLock;
        this.enabled = enabled;
        this.constantFlashMode = constantFlashMode;
        this.markConstantLightingEngaged = markConstantLightingEngaged;
    }

    public void setAfterBrightnessApplied(Runnable hook) {
        this.afterBrightnessApplied = hook;
    }

    public void setCaptureLightingActive(BooleanSupplier captureLightingActive) {
        this.captureLightingActive = captureLightingActive;
    }

    public boolean hasDeferredHardwareBrightness() {
        return deferredHardwareBrightness;
    }

    public LightBrightnessApplyResult apply(LightBrightnessUpdate update) {
        if (update == null || update.isEmpty()) {
            return LightBrightnessApplyResult.none();
        }
        boolean deferHardware = shouldDeferHardwareBrightness();
        LightBrightnessApplyResult result = LightBrightnessApplyResult.none();
        if (update.globalPercent() != null) {
            result = LightBrightnessApplyResult.merge(
                    result,
                    applyGlobalBrightness(update.globalPercent(), deferHardware)
            );
        }
        for (Map.Entry<String, Integer> entry : update.perEndpoint().entrySet()) {
            result = LightBrightnessApplyResult.merge(
                    result,
                    applyEndpointBrightness(entry.getKey(), entry.getValue(), deferHardware)
            );
        }
        if (deferHardware) {
            deferredHardwareBrightness = true;
            log.info("light brightness deferred until after capture Off (memory updated)");
            return result;
        }
        runAfterBrightnessApplied();
        if (constantFlashMode.getAsBoolean() && enabled && !result.hasHardwareErrors()) {
            try {
                synchronized (lightCommandLock) {
                    // Brightness endpoints update registers without On. Reassert the bank so the new
                    // brightness is visible immediately and constant mode remains continuously lit.
                    transport.postBankState("on", "constant-mode brightness bank-On");
                    markConstantLightingEngaged.run();
                }
            } catch (RuntimeException e) {
                result = LightBrightnessApplyResult.merge(
                        result,
                        new LightBrightnessApplyResult(List.of(
                                "constant bank-On: " + LightServerHttpTransport.formatError(e)))
                );
            }
        }
        return result;
    }

    /**
     * После Off по кадру: дописать отложенную яркость в LightServer.
     * Следующий штатный bank On (idle/re-engage/DI3) применит через ApplyDirectOn.
     */
    public LightBrightnessApplyResult flushDeferred() {
        List<LightServersConfig.CameraFlashSpec> toPush;
        synchronized (lightCommandLock) {
            if (!deferredHardwareBrightness) {
                return LightBrightnessApplyResult.none();
            }
            deferredHardwareBrightness = false;
            if (!enabled) {
                return LightBrightnessApplyResult.disabled();
            }
            toPush = brightnessMemory.snapshot();
        }
        log.info("light brightness flush after capture Off cameras={}", toPush.size());
        return new LightBrightnessApplyResult(engage.pushCameraBrightnessIfEnabled(toPush));
    }

    private boolean shouldDeferHardwareBrightness() {
        if (constantFlashMode.getAsBoolean()) {
            return false;
        }
        BooleanSupplier gate = captureLightingActive;
        return gate != null && gate.getAsBoolean();
    }

    private void runAfterBrightnessApplied() {
        Runnable hook = afterBrightnessApplied;
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (RuntimeException e) {
            log.warn("afterBrightnessApplied: {}", e.getMessage());
        }
    }

    private LightBrightnessApplyResult applyGlobalBrightness(int percent, boolean memoryOnly) {
        int clamped = LightBrightnessScale.clampPercent(percent);
        List<LightServersConfig.CameraFlashSpec> toPush;
        synchronized (lightCommandLock) {
            brightnessMemory.setDefaultBrightnessPercent(clamped);
            List<LightServersConfig.CameraFlashSpec> cameras = brightnessMemory.snapshot();
            toPush = new ArrayList<>(cameras.size());
            for (LightServersConfig.CameraFlashSpec existing : cameras) {
                toPush.add(brightnessMemory.replaceCameraBrightnessMemory(
                        existing.cameraId(), clamped, clamped, clamped));
            }
        }
        if (memoryOnly) {
            return LightBrightnessApplyResult.none();
        }
        if (!enabled) {
            return LightBrightnessApplyResult.disabled();
        }
        return new LightBrightnessApplyResult(engage.pushCameraBrightnessIfEnabled(toPush));
    }

    private LightBrightnessApplyResult applyEndpointBrightness(String endpointId, int percent, boolean memoryOnly) {
        Integer cameraId = LightBrightnessMemory.parseCameraIdFromEndpoint(endpointId);
        if (cameraId == null) {
            throw new IllegalArgumentException("unknown light endpoint id: " + endpointId);
        }
        int clamped = LightBrightnessScale.clampPercent(percent);
        LightServersConfig.CameraFlashSpec toPush;
        synchronized (lightCommandLock) {
            toPush = brightnessMemory.replaceCameraBrightnessMemory(cameraId, clamped, clamped, clamped);
        }
        if (memoryOnly) {
            return LightBrightnessApplyResult.none();
        }
        if (!enabled) {
            return LightBrightnessApplyResult.disabled();
        }
        return new LightBrightnessApplyResult(engage.pushCameraBrightnessIfEnabled(toPush));
    }
}
