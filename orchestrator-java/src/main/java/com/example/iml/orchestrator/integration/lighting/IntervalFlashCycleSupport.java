package com.example.iml.orchestrator.integration.lighting;

import org.apache.logging.log4j.Logger;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** On/Off scheduling and bank engage for {@link IntervalFlashController}. */
final class IntervalFlashCycleSupport {

    private final Logger log;
    private final IntervalFlashController.Lights lights;
    private final IntervalFlashConfig config;
    private final ScheduledExecutorService lightExecutor;
    private final AtomicBoolean lightsOn;
    private final AtomicBoolean awaitingFrameOff;
    private final AtomicBoolean captureCycleActive;
    private volatile ScheduledFuture<?> pendingOff;
    private volatile ScheduledFuture<?> pendingOn;
    private volatile boolean idleOnAfterOff;
    private volatile String idleOnAfterOffReason;
    private volatile Runnable flushDeferredBrightness;

    IntervalFlashCycleSupport(
            Logger log,
            IntervalFlashController.Lights lights,
            IntervalFlashConfig config,
            ScheduledExecutorService lightExecutor,
            AtomicBoolean lightsOn,
            AtomicBoolean awaitingFrameOff,
            AtomicBoolean captureCycleActive
    ) {
        this.log = log;
        this.lights = lights;
        this.config = config;
        this.lightExecutor = lightExecutor;
        this.lightsOn = lightsOn;
        this.awaitingFrameOff = awaitingFrameOff;
        this.captureCycleActive = captureCycleActive;
    }

    void setFlushDeferredBrightness(Runnable flush) {
        this.flushDeferredBrightness = flush;
    }

    boolean captureLightingActive() {
        if (captureCycleActive.get() || awaitingFrameOff.get()) {
            return true;
        }
        ScheduledFuture<?> off = pendingOff;
        return off != null && !off.isDone();
    }

    void scheduleOn(String reason) {
        cancelPendingOn();
        lightExecutor.execute(() -> engageLights(reason));
    }

    /** Холостой On: сразу, если нет отложенного Off; иначе — после Off. */
    void requestIdleOn(String reason) {
        ScheduledFuture<?> off = pendingOff;
        if (off != null && !off.isDone()) {
            idleOnAfterOff = true;
            idleOnAfterOffReason = reason;
            log.info("interval_flash idle On deferred until Off ({})", reason);
            return;
        }
        idleOnAfterOff = false;
        scheduleOn(reason);
    }

    void engageLights(String reason) {
        if (lights.constantMode()) {
            return;
        }
        log.info("interval_flash On ({})", reason);
        boolean ok = lights.lightAllOn("interval_flash");
        lightsOn.set(ok);
        if (!ok) {
            log.warn("interval_flash On failed ({})", reason);
        }
    }

    void scheduleOff() {
        cancelPendingOff();
        idleOnAfterOff = false;
        int delayMs = config.offDelayMs();
        if (delayMs <= 0) {
            lightExecutor.execute(this::extinguishLights);
            return;
        }
        log.info(
                "interval_flash Off scheduled in {} ms (DI{} {}, off_on_first_frame={})",
                delayMs,
                config.triggerPort(),
                config.triggerEdge().name().toLowerCase(),
                config.offOnFirstFrame()
        );
        pendingOff = lightExecutor.schedule(this::extinguishLights, delayMs, TimeUnit.MILLISECONDS);
    }

    void extinguishLights() {
        if (lights.constantMode()) {
            pendingOff = null;
            awaitingFrameOff.set(false);
            captureCycleActive.set(false);
            return;
        }
        pendingOff = null;
        awaitingFrameOff.set(false);
        captureCycleActive.set(false); // exit capture window before flush
        log.info(
                "interval_flash Off (DI{} {})",
                config.triggerPort(),
                config.triggerEdge().name().toLowerCase()
        );
        lights.forceAllOff();
        lightsOn.set(false);
        flushDeferredBrightnessAfterCapture();
        if (idleOnAfterOff) {
            idleOnAfterOff = false;
            String reason = idleOnAfterOffReason != null ? idleOnAfterOffReason : "idle-after-off";
            engageLights(reason);
            return;
        }
        scheduleReengage("off");
    }

    private void flushDeferredBrightnessAfterCapture() {
        Runnable flush = flushDeferredBrightness;
        if (flush == null) {
            return;
        }
        try {
            flush.run();
        } catch (RuntimeException e) {
            log.warn("interval_flash flushDeferredBrightness: {}", e.getMessage());
        }
    }

    void scheduleReengage(String afterPhase) {
        if (lights.constantMode()) {
            return;
        }
        cancelPendingOn();
        int delayMs = config.onReengageDelayMs();
        if (delayMs <= 0) {
            return;
        }
        log.info("interval_flash On scheduled in {} ms (after {})", delayMs, afterPhase);
        pendingOn = lightExecutor.schedule(
                () -> {
                    pendingOn = null;
                    engageLights("auto re-engage after " + delayMs + " ms");
                },
                delayMs,
                TimeUnit.MILLISECONDS
        );
    }

    void cancelPendingOff() {
        ScheduledFuture<?> task = pendingOff;
        pendingOff = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    void cancelPendingOn() {
        ScheduledFuture<?> task = pendingOn;
        pendingOn = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    void executeOffOnFirstFrame() {
        lightExecutor.execute(this::extinguishLights);
    }

    void executeForceOff() {
        lightExecutor.execute(() -> {
            lights.forceAllOff();
            lightsOn.set(false);
        });
    }

    void beginCaptureCycle() {
        captureCycleActive.set(true);
        awaitingFrameOff.set(config.offOnFirstFrame());
    }

    void clearCaptureFlags() {
        awaitingFrameOff.set(false);
        captureCycleActive.set(false);
    }
}
