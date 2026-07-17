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
 * Отдельный контур вспышек (не участвует в capture/inspection):
 * <ul>
 *   <li>холостой ход (DI idle, обычно DI2↓) → On</li>
 *   <li>DI3↑ → On и Off через {@code off_delay_ms}</li>
 *   <li>после Off → авто-On через {@code on_reengage_delay_ms}</li>
 * </ul>
 * HTTP к LightServer только в своём single-thread executor — callback DI не блокируется.
 */
public final class IntervalFlashController implements AutoCloseable {

    public interface Lights {
        boolean lightAllOn(String phase);

        void forceAllOff();
    }

    private final Logger log;
    private final Lights lights;
    private final IntervalFlashConfig config;
    /** Все On/Off сериализованы здесь — пайплайн съёмки не ждёт HTTP. */
    private final ScheduledExecutorService lightExecutor;
    private final AtomicBoolean lightsOn = new AtomicBoolean(false);
    private final Object stateLock = new Object();

    private boolean idlePortActive;
    private boolean triggerPortActive;
    private boolean idlePortInitialized;
    private boolean triggerPortInitialized;
    private volatile ScheduledFuture<?> pendingOff;
    private volatile ScheduledFuture<?> pendingOn;
    /** DI2 idle On отложен, пока не выполнится Off после DI3 (нельзя cancelPendingOff). */
    private volatile boolean idleOnAfterOff;
    private volatile String idleOnAfterOffReason;

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
        lightExecutor.execute(() -> {
            lights.forceAllOff();
            lightsOn.set(false);
        });
        log.info(
                "interval_flash start_dark: холостой DI{} {} → On; DI{} {} → On + Off (delay_ms={}); авто-On через {} ms",
                config.idlePort(),
                config.idleEdge().name().toLowerCase(),
                config.triggerPort(),
                config.triggerEdge().name().toLowerCase(),
                config.offDelayMs(),
                config.onReengageDelayMs()
        );
        scheduleReengage("start_dark");
    }

    /**
     * Сырой DI от IoInputMonitor. Должен возвращаться быстро — без HTTP.
     */
    public void onDiChange(IoInputDiChange change) {
        if (!config.enabled() || change == null) {
            return;
        }
        int port = change.diPort();
        boolean active = change.active();
        if (port == config.idlePort()) {
            if (config.idleOnEnabled()) {
                handleIdlePort(active);
            }
        } else if (port == config.triggerPort()) {
            handleTriggerPort(active);
        }
    }

    /**
     * После /pair (WriteBrightness): один Off→On→Off, чтобы MV-LE зафиксировал яркость.
     * Следующий DI3 On уже из Off — без лишнего Off на каждом кадре.
     */
    public void onBrightnessUpdated() {
        if (!config.enabled()) {
            return;
        }
        lightExecutor.execute(() -> {
            cancelPendingOff();
            cancelPendingOn();
            lights.forceAllOff();
            lightsOn.set(false);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean ok = lights.lightAllOn("brightness-refresh");
            if (!ok) {
                log.warn("interval_flash brightness-refresh On failed");
                return;
            }
            lightsOn.set(true);
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lights.forceAllOff();
            lightsOn.set(false);
            log.info("interval_flash brightness-refresh latched (Off→On→Off)");
        });
    }

    private void handleIdlePort(boolean active) {
        boolean edge;
        boolean alreadyIdle;
        synchronized (stateLock) {
            if (!idlePortInitialized) {
                idlePortInitialized = true;
                idlePortActive = active;
                alreadyIdle = isIdleLevel(active, config.idleEdge());
                edge = false;
            } else {
                alreadyIdle = false;
                edge = isEdge(idlePortActive, active, config.idleEdge());
                idlePortActive = active;
            }
        }
        if (edge || alreadyIdle) {
            // Не отменяем pending Off: иначе при быстром DI2↓ Off после DI3 не срабатывает,
            // банк остаётся On на весь следующий цикл → «через цикл очень ярко».
            requestIdleOn("холостой DI" + config.idlePort() + " "
                    + (alreadyIdle ? "level" : config.idleEdge().name().toLowerCase()));
        }
    }

    private void handleTriggerPort(boolean active) {
        boolean edge;
        synchronized (stateLock) {
            if (!triggerPortInitialized) {
                triggerPortInitialized = true;
                triggerPortActive = active;
                // Начальный уровень DI3 без фронта — не трогаем (избегаем ложного Off при старте).
                return;
            }
            edge = isEdge(triggerPortActive, active, config.triggerEdge());
            triggerPortActive = active;
        }
        if (edge) {
            // Съёмка на DI3: гарантируем On и гасим после экспозиции / DO5 pulse.
            cancelPendingOff();
            cancelPendingOn();
            scheduleOn("DI" + config.triggerPort() + " " + config.triggerEdge().name().toLowerCase());
            scheduleOff();
        }
    }

    private void scheduleOn(String reason) {
        cancelPendingOn();
        lightExecutor.execute(() -> engageLights(reason));
    }

    /**
     * Холостой On: сразу, если нет отложенного Off; иначе — после Off.
     */
    private void requestIdleOn(String reason) {
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

    private void engageLights(String reason) {
        log.info("interval_flash On ({})", reason);
        boolean ok = lights.lightAllOn("interval_flash");
        lightsOn.set(ok);
        if (!ok) {
            log.warn("interval_flash On failed ({})", reason);
        }
    }

    private void scheduleOff() {
        cancelPendingOff();
        idleOnAfterOff = false;
        int delayMs = config.offDelayMs();
        if (delayMs <= 0) {
            lightExecutor.execute(this::extinguishLights);
            return;
        }
        log.info(
                "interval_flash Off scheduled in {} ms (DI{} {})",
                delayMs,
                config.triggerPort(),
                config.triggerEdge().name().toLowerCase()
        );
        pendingOff = lightExecutor.schedule(this::extinguishLights, delayMs, TimeUnit.MILLISECONDS);
    }

    private void extinguishLights() {
        pendingOff = null;
        log.info(
                "interval_flash Off (DI{} {})",
                config.triggerPort(),
                config.triggerEdge().name().toLowerCase()
        );
        lights.forceAllOff();
        lightsOn.set(false);
        if (idleOnAfterOff) {
            idleOnAfterOff = false;
            String reason = idleOnAfterOffReason != null ? idleOnAfterOffReason : "idle-after-off";
            engageLights(reason);
            return;
        }
        scheduleReengage("off");
    }

    private void scheduleReengage(String afterPhase) {
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

    /**
     * Уже в «холостом» уровне при первом сэмпле порта (без фронта).
     * FALLING → active=false; RISING → active=true.
     */
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

    /** Тесты: дождаться опустошения очереди On/Off (включая delay=0). */
    void awaitLightTasks(long timeoutMs) throws Exception {
        lightExecutor.submit(() -> null).get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        cancelPendingOff();
        cancelPendingOn();
        // Синхронно гасим до shutdown executor: иначе при kill/Ctrl+C банк остаётся On (DI2 idle).
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
