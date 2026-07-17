package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Отдельный контур вспышек: DI2↑ → On, DI3↑ → Off (+ {@code off_delay_ms}),
 * затем авто-On через {@code on_reengage_delay_ms} после гашения.
 * Не участвует в capture/inspection pipeline — только HTTP к LightServer.
 */
public final class IntervalFlashController implements AutoCloseable {

    public interface Lights {
        boolean lightAllOn(String phase);

        void forceAllOff();
    }

    private final Logger log;
    private final Lights lights;
    private final IntervalFlashConfig config;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean lightsOn = new AtomicBoolean(false);
    private final Object stateLock = new Object();

    private boolean onPortActive;
    private boolean offPortActive;
    private boolean onPortInitialized;
    private boolean offPortInitialized;
    private volatile ScheduledFuture<?> pendingOff;
    private volatile ScheduledFuture<?> pendingOn;

    public IntervalFlashController(Logger log, LightTriggerClient lightClient, IntervalFlashConfig config) {
        this(log, asLights(lightClient), config);
    }

    public IntervalFlashController(Logger log, Lights lights, IntervalFlashConfig config) {
        this.log = log;
        this.lights = lights;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
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
        };
    }

    public IntervalFlashConfig config() {
        return config;
    }

    /** После startupEngage: погасить свет и ждать фронт On. */
    public void armStartDark() {
        if (!config.enabled() || !config.startDark()) {
            return;
        }
        cancelPendingOff();
        cancelPendingOn();
        lights.forceAllOff();
        lightsOn.set(false);
        log.info(
                "interval_flash start_dark: ждём DI{} {} → On, DI{} {} → Off (delay_ms={}), авто-On через {} ms",
                config.onPort(),
                config.onEdge().name().toLowerCase(),
                config.offPort(),
                config.offEdge().name().toLowerCase(),
                config.offDelayMs(),
                config.onReengageDelayMs()
        );
        scheduleReengage("start_dark");
    }

    public void onDiChange(IoInputDiChange change) {
        if (!config.enabled() || change == null) {
            return;
        }
        int port = change.diPort();
        boolean active = change.active();
        if (port == config.onPort()) {
            handleOnPort(active);
        } else if (port == config.offPort()) {
            handleOffPort(active);
        }
    }

    private void handleOnPort(boolean active) {
        boolean edge;
        synchronized (stateLock) {
            if (!onPortInitialized) {
                onPortInitialized = true;
                onPortActive = active;
                // Начальное состояние без фронта — не включаем (избегаем ложного On при старте).
                return;
            }
            edge = isEdge(onPortActive, active, config.onEdge());
            onPortActive = active;
        }
        if (edge) {
            cancelPendingOff();
            cancelPendingOn();
            engageLights("DI" + config.onPort() + " " + config.onEdge().name().toLowerCase());
        }
    }

    private void handleOffPort(boolean active) {
        boolean edge;
        synchronized (stateLock) {
            if (!offPortInitialized) {
                offPortInitialized = true;
                offPortActive = active;
                return;
            }
            edge = isEdge(offPortActive, active, config.offEdge());
            offPortActive = active;
        }
        if (edge) {
            scheduleOff();
        }
    }

    private void engageLights(String reason) {
        log.info("interval_flash On ({})", reason);
        boolean ok = lights.lightAllOn("interval_flash");
        lightsOn.set(ok);
        if (!ok) {
            log.warn("interval_flash On failed");
        }
    }

    private void scheduleOff() {
        cancelPendingOff();
        int delayMs = config.offDelayMs();
        if (delayMs <= 0) {
            extinguishLights();
            return;
        }
        log.info(
                "interval_flash Off scheduled in {} ms (DI{} {})",
                delayMs,
                config.offPort(),
                config.offEdge().name().toLowerCase()
        );
        pendingOff = scheduler.schedule(this::extinguishLights, delayMs, TimeUnit.MILLISECONDS);
    }

    private void extinguishLights() {
        log.info("interval_flash Off (DI{} {})", config.offPort(), config.offEdge().name().toLowerCase());
        lights.forceAllOff();
        lightsOn.set(false);
        scheduleReengage("off");
    }

    private void scheduleReengage(String afterPhase) {
        cancelPendingOn();
        int delayMs = config.onReengageDelayMs();
        if (delayMs <= 0) {
            return;
        }
        log.info("interval_flash On scheduled in {} ms (after {})", delayMs, afterPhase);
        pendingOn = scheduler.schedule(
                () -> engageLights("auto re-engage after " + delayMs + " ms"),
                delayMs,
                TimeUnit.MILLISECONDS
        );
    }

    private void cancelPendingOff() {
        ScheduledFuture<?> task = pendingOff;
        pendingOff = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private void cancelPendingOn() {
        ScheduledFuture<?> task = pendingOn;
        pendingOn = null;
        if (task != null) {
            task.cancel(false);
        }
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

    @Override
    public void close() {
        cancelPendingOff();
        cancelPendingOn();
        scheduler.shutdownNow();
    }
}
