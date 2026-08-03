package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Интервальные вспышки по импульсу DI (обычно DI2):
 * <ul>
 *   <li>начало импульса (Rising / active=true) → bank On</li>
 *   <li>конец импульса (Falling / active=false) → bank Off</li>
 * </ul>
 * DI3 и кадры съёмки не управляют светом. HTTP только в своём single-thread executor.
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

    /** Пока импульс DI2 активен (банк должен быть On). */
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
                "interval_flash start_dark: DI{}↑ → On; DI{}↓ → Off (импульс направления)",
                config.idlePort(),
                config.idlePort()
        );
    }

    /**
     * Сырой DI от IoInputMonitor. Должен возвращаться быстро — без HTTP.
     */
    public void onDiChange(IoInputDiChange change) {
        if (!config.enabled() || change == null || lights.constantMode()) {
            return;
        }
        if (change.diPort() != config.idlePort()) {
            return;
        }
        handlePulsePort(change.active());
    }

    /**
     * Раньше гасили по первому кадру; теперь Off только по DI2↓ — оставляем no-op для wiring.
     */
    public void onFirstFrameCaptured(int cameraId) {
        // no-op: Off по концу импульса DI2
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
        }
        if (rising) {
            pulseActive.set(true);
            scheduleOn("DI" + config.idlePort() + " rising");
        } else if (falling) {
            pulseActive.set(false);
            scheduleOff("DI" + config.idlePort() + " falling");
        }
    }

    private void scheduleOn(String reason) {
        lightExecutor.execute(() -> engageLights(reason));
    }

    private void scheduleOff(String reason) {
        lightExecutor.execute(() -> extinguishLights(reason));
    }

    private void engageLights(String reason) {
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

    private void extinguishLights(String reason) {
        if (lights.constantMode()) {
            pulseActive.set(false);
            return;
        }
        pulseActive.set(false);
        log.info("interval_flash Off ({})", reason);
        lights.forceAllOff();
        lightsOn.set(false);
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
