package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import com.example.iml.orchestrator.integration.camera.NativeCameraEvents;
import java.util.Set;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Интервальные вспышки по импульсу DI (обычно DI2):
 * <ul>
 *   <li>начало импульса (Rising / active=true) → bank On after on_delay_ms</li>
 *   <li>конец импульса (Falling / active=false) → bank Off</li>
 * </ul>
 * Optional ExposureEnd from all cameras can extinguish early after validation; DI falling is fallback.
 * All light calls run on the single light executor, outside SDK event callbacks.
 */
public final class IntervalFlashController implements AutoCloseable {

    public interface Lights {
        boolean lightAllOn(String phase);

        void forceAllOff();

        default boolean constantMode() {
            return false;
        }
    }

    private final Logger log;
    private final Lights lights;
    private final IntervalFlashConfig config;
    /** Все On/Off сериализованы здесь — пайплайн съёмки не ждёт HTTP. */
    private final ScheduledExecutorService lightExecutor;
    private final AtomicBoolean lightsOn = new AtomicBoolean(false);
    /** Окно импульса DI2: яркость/latch не трогаем, пока импульс активен. */
    private final AtomicBoolean pulseActive = new AtomicBoolean(false);
    private final Object stateLock = new Object();
    private ExposureEndFlashGate exposureGate;
    private AutoCloseable exposureSubscription;
    private long pulseGeneration;
    private long pulseStartedNs;
    private ScheduledFuture<?> pendingOn;
    private boolean exposureOffScheduled;
    private volatile boolean closed;

    private boolean pulsePortInitialized;
    private boolean pulsePortLevel;
    /** После Off по DI2↓: flush отложенной яркости в LightServer. */
    private volatile Runnable flushDeferredBrightness;

    public IntervalFlashController(Logger log, LightTriggerClient lightClient, IntervalFlashConfig config) {
        this(log, asLights(lightClient), config);
    }

    public IntervalFlashController(Logger log, Lights lights, IntervalFlashConfig config) {
        this.log = log;
        this.lights = lights;
        this.config = config;
        this.lightExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "interval-flash");
            t.setDaemon(true);
            return t;
        });
    }

    private static Lights asLights(LightTriggerClient client) {
        return new Lights() {
            @Override
            public boolean lightAllOn(String phase) {
                return client.bankAllOn(phase);
            }

            @Override
            public void forceAllOff() {
                client.bankAllOff();
            }

            @Override
            public boolean constantMode() {
                return client.isConstantFlashMode();
            }
        };
    }

    public IntervalFlashConfig config() {
        return config;
    }

    public void setFlushDeferredBrightness(Runnable flush) {
        this.flushDeferredBrightness = flush;
    }

    public void enableExposureEnd(Set<Integer> cameraIds) {
        synchronized (stateLock) {
            if (!config.offOnExposureEnd() || exposureSubscription != null || closed) return;
            exposureGate = new ExposureEndFlashGate(cameraIds, config.exposureEndValidationCycles());
            exposureSubscription = NativeCameraEvents.subscribe(this::onCameraEvent);
        }
        log.info("interval_flash ExposureEnd validation cameras={} cycles={} fallback=DI{} falling",
                cameraIds, config.exposureEndValidationCycles(), config.idlePort());
    }

    public void onCameraEvent(NativeCameraEvents.Event event) {
        synchronized (stateLock) {
            if (closed || lights.constantMode() || exposureGate == null || !exposureGate.accept(event)) return;
            if (exposureOffScheduled) return;
            exposureOffScheduled = true;
            pulseActive.set(false);
            cancelPendingOn();
            long generation = pulseGeneration;
            lightExecutor.execute(() -> {
                synchronized (stateLock) { if (closed || generation != pulseGeneration) return; }
                log.info("interval_flash event=exposure_end_complete generation={} last_camera={} event_age_ms={}",
                        generation, event.cameraId(), (System.nanoTime() - event.hostMonotonicNs()) / 1_000_000.0);
                extinguishLights("ExposureEnd all cameras");
            });
        }
    }

    /** Lighting remains reserved until all exposures end, or the DI window closes. */
    public boolean captureLightingActive() {
        return pulseActive.get();
    }

    /** После startupEngage: погасить свет и ждать DI2↑. */
    public void armStartDark() {
        if (!config.enabled() || !config.startDark() || lights.constantMode()) {
            return;
        }
        pulseActive.set(false);
        lightExecutor.execute(() -> {
            lights.forceAllOff();
            lightsOn.set(false);
        });
        log.info(
                "interval_flash start_dark: DI{}↑ + {} ms → On; DI{}↓ → Off (импульс направления)",
                config.idlePort(),
                config.onDelayMs(),
                config.idlePort()
        );
    }

    /**
     * Сырой DI от IO source. Должен возвращаться быстро — без HTTP.
     */
    public void onDiChange(IoInputDiChange change) {
        if (closed || !config.enabled() || change == null || lights.constantMode()) {
            return;
        }
        if (change.diPort() != config.idlePort()) {
            return;
        }
        handlePulsePort(change.active());
    }

    /**
     * Frame reception does not drive lighting; use ExposureEnd or DI falling.
     */
    public void onFirstFrameCaptured(int cameraId) {
        // no-op: receiving a frame is later than ExposureEnd.
    }

    /**
     * Яркость не требует Off→On→Off; wiring совместимости — no-op.
     */
    public void onBrightnessUpdated() {
        // no-op
    }

    private void handlePulsePort(boolean active) {
        boolean rising;
        boolean falling;
        synchronized (stateLock) {
            boolean firstSample = !pulsePortInitialized;
            if (!pulsePortInitialized) {
                pulsePortInitialized = true;
                pulsePortLevel = active;
                rising = active;
                falling = !active;
            } else {
                rising = !pulsePortLevel && active;
                falling = pulsePortLevel && !active;
                pulsePortLevel = active;
            }
            if (rising) {
                pulseGeneration++;
                pulseStartedNs = System.nanoTime();
                exposureOffScheduled = false;
                if (exposureGate != null) exposureGate.begin(System.nanoTime());
                pulseActive.set(true);
                scheduleOn("DI" + config.idlePort() + " rising", firstSample);
            } else if (falling) {
                if (exposureGate != null) {
                    boolean good = exposureGate.finish();
                    log.info("interval_flash event=exposure_end_validation complete={} consecutive={} required={} early_off={}",
                            good, exposureGate.consecutive(), exposureGate.requiredCycles(), exposureOffScheduled);
                }
                pulseActive.set(false);
                cancelPendingOn();
                scheduleOff("DI" + config.idlePort() + " falling");
            }
        }
    }

    private void scheduleOn(String reason, boolean initialHigh) {
        if (config.onDelayMs() <= 0) {
            lightExecutor.execute(() -> engageLights(reason));
            return;
        }
        cancelPendingOn();
        long generation = pulseGeneration;
        long startedNs = pulseStartedNs;
        // An initial HIGH is a level snapshot, not a timestamped rising edge.
        // Its exposure may be imminent; apply the delay only after observing LOW.
        int delayMs = initialHigh ? 0 : config.onDelayMs();
        log.info("interval_flash event=bank_on_scheduled generation={} delay_ms={} initial_high={}",
                generation, delayMs, initialHigh);
        pendingOn = lightExecutor.schedule(() -> {
            synchronized (stateLock) {
                if (closed || generation != pulseGeneration || !pulseActive.get() || exposureOffScheduled) return;
                pendingOn = null;
            }
            log.info("interval_flash event=bank_on_started generation={} di2_age_ms={}",
                    generation, (System.nanoTime() - startedNs) / 1_000_000.0);
            long commandStartedNs = System.nanoTime();
            engageLights(reason);
            log.info("interval_flash event=bank_on_complete generation={} ok={} duration_ms={} di2_age_ms={}",
                    generation, lightsOn.get(), (System.nanoTime() - commandStartedNs) / 1_000_000.0,
                    (System.nanoTime() - startedNs) / 1_000_000.0);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Caller holds stateLock; commands already in flight are followed by the queued Off. */
    private void cancelPendingOn() {
        if (pendingOn != null) {
            pendingOn.cancel(false);
            pendingOn = null;
        }
    }

    private void scheduleOff(String reason) {
        lightExecutor.execute(() -> extinguishLights(reason));
    }

    private void engageLights(String reason) {
        if (closed || lights.constantMode()) {
            return;
        }
        log.info("interval_flash On ({})", reason);
        boolean ok = lights.lightAllOn("interval_flash");
        lightsOn.set(ok);
        if (!ok) {
            log.warn("interval_flash On failed ({})", reason);
        }
    }

    private void extinguishLights(String reason) {
        if (lights.constantMode()) {
            return;
        }
        log.info("interval_flash Off ({})", reason);
        long started = System.nanoTime();
        lights.forceAllOff();
        lightsOn.set(false);
        log.info("interval_flash event=bank_off_complete reason={} duration_ms={}", reason,
                (System.nanoTime() - started) / 1_000_000.0);
        flushDeferredBrightnessAfterCapture();
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

    static boolean isIdleLevel(boolean active, TriggerEdgeMode edge) {
        if (edge == TriggerEdgeMode.FALLING) {
            return !active;
        }
        return active;
    }

    static boolean isEdge(boolean previous, boolean current, TriggerEdgeMode edge) {
        if (edge == TriggerEdgeMode.FALLING) {
            return previous && !current;
        }
        return !previous && current;
    }

    public boolean lightsOn() {
        return lightsOn.get();
    }

    /** Тесты: дождаться опустошения очереди On/Off. */
    void awaitLightTasks(long timeoutMs) throws Exception {
        lightExecutor.submit(() -> null).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            closed = true;
            pulseGeneration++;
            cancelPendingOn();
            if (exposureSubscription != null) {
                try { exposureSubscription.close(); }
                catch (Exception e) { log.warn("ExposureEnd unsubscribe failed", e); }
                exposureSubscription = null;
            }
        }
        pulseActive.set(false);
        try {
            lights.forceAllOff();
            lightsOn.set(false);
            log.info("interval_flash close: bank Off");
        } catch (Exception e) {
            log.warn("interval_flash close forceAllOff: {}", e.getMessage());
        }
        lightExecutor.shutdownNow();
    }
}
