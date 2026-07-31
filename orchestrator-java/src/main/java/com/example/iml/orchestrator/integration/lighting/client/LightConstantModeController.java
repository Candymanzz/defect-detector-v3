package com.example.iml.orchestrator.integration.lighting.client;

import com.example.iml.orchestrator.integration.lighting.LightingException;
import org.apache.logging.log4j.Logger;

/** Constant-flash and startup engage logic for LightTriggerClient. */
public final class LightConstantModeController {

    private final Logger log;
    private final LightEngageCommander engage;
    private final LightServerHttpTransport transport;
    private final Object lightCommandLock;
    private final boolean enabled;
    private final boolean failOnError;
    private final boolean holdMode;
    private volatile boolean constantLightingEngaged;
    private volatile boolean constantFlashMode;

    public LightConstantModeController(
            Logger log,
            LightEngageCommander engage,
            LightServerHttpTransport transport,
            Object lightCommandLock,
            boolean enabled,
            boolean failOnError,
            boolean holdMode
    ) {
        this.log = log;
        this.engage = engage;
        this.transport = transport;
        this.lightCommandLock = lightCommandLock;
        this.enabled = enabled;
        this.failOnError = failOnError;
        this.holdMode = holdMode;
    }

    public boolean isConstantFlashMode() {
        return constantFlashMode;
    }

    public boolean isConstantLightingEngaged() {
        return constantLightingEngaged;
    }

    public void markConstantLightingEngaged() {
        constantLightingEngaged = true;
    }

    public void setConstantFlashMode(boolean constant) {
        synchronized (lightCommandLock) {
            if (constantFlashMode == constant) {
                return;
            }
            if (!enabled) {
                constantFlashMode = constant;
                return;
            }
            if (constant) {
                if (!engage.engageLightingLocked()) {
                    // fail_on_error=false: не валим весь bootstrap из‑за одной COM/Ethernet вспышки.
                    if (failOnError) {
                        throw new LightingException("failed to enable constant flash mode");
                    }
                    log.warn("constant flash mode: brightness apply partial failure — continuing with bank On");
                }
                // /pair and /single only write brightness registers; they deliberately do not turn LEDs on.
                // Constant mode therefore always needs an explicit bank On after brightness is prepared.
                transport.postBankState("on", "constant-mode bank-On");
                constantFlashMode = true;
                constantLightingEngaged = true;
            } else {
                engage.postOffWithRetriesLocked();
                constantFlashMode = false;
                constantLightingEngaged = false;
            }
            log.info("light flash mode changed to {}", constant ? "constant" : "interval");
        }
    }

    /**
     * При старте оркестратора: выставить яркость по камерам и включить вспышки.
     * В {@code hold_mode} подсветка остаётся включённой между кадрами.
     */
    public void startupEngage() {
        if (!enabled) {
            return;
        }
        synchronized (lightCommandLock) {
            if (holdMode && constantLightingEngaged) {
                return;
            }
            log.info("light startup: яркость по камерам (hold_mode={})", holdMode);
            if (!engage.engageLightingLocked()) {
                log.warn("light startup: не удалось включить подсветку — capture продолжит без блокирующей повторной инициализации");
                return;
            }
            if (holdMode) {
                constantLightingEngaged = true;
            }
            engage.sleepSettle();
        }
    }
}
