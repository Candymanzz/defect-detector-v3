package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Отдельный контур вспышек (не участвует в capture/inspection):
 * <ul>
 *   <li>холостой ход (DI idle, обычно DI2↓) → On</li>
 *   <li>DI3↑ → On и Off через {@code off_delay_ms} (или раньше по первому кадру)</li>
 *   <li>после Off → авто-On через {@code on_reengage_delay_ms}</li>
 * </ul>
 * HTTP к LightServer только в своём single-thread executor — callback DI не блокируется.
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
    private final ScheduledExecutorService lightExecutor;
    private final AtomicBoolean lightsOn = new AtomicBoolean(false);
    private final AtomicBoolean awaitingFrameOff = new AtomicBoolean(false);
    private final AtomicBoolean captureCycleActive = new AtomicBoolean(false);
    private final IntervalFlashCycleSupport cycle;
    private final IntervalFlashDiHandlers diHandlers;

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
        this.cycle = new IntervalFlashCycleSupport(
                log, lights, config, lightExecutor, lightsOn, awaitingFrameOff, captureCycleActive);
        this.diHandlers = new IntervalFlashDiHandlers(config, cycle);
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

    public void setFlushDeferredBrightness(Runnable flush) {
        cycle.setFlushDeferredBrightness(flush);
    }

    /** Окно съёмки: DI3 On до Off после кадра (или timeout). */
    public boolean captureLightingActive() {
        return cycle.captureLightingActive();
    }

    /** После startupEngage: погасить свет и ждать фронт On. */
    public void armStartDark() {
        if (!config.enabled() || !config.startDark() || lights.constantMode()) {
            return;
        }
        cycle.cancelPendingOff();
        cycle.cancelPendingOn();
        cycle.clearCaptureFlags();
        cycle.executeForceOff();
        log.info(
                "interval_flash start_dark: холостой DI{} {} → On; DI{} {} → On + Off (delay_ms={}, off_on_first_frame={}); авто-On через {} ms",
                config.idlePort(),
                config.idleEdge().name().toLowerCase(),
                config.triggerPort(),
                config.triggerEdge().name().toLowerCase(),
                config.offDelayMs(),
                config.offOnFirstFrame(),
                config.onReengageDelayMs()
        );
        cycle.scheduleReengage("start_dark");
    }

    /** Сырой DI от IoInputMonitor. Должен возвращаться быстро — без HTTP. */
    public void onDiChange(IoInputDiChange change) {
        if (!config.enabled() || change == null || lights.constantMode()) {
            return;
        }
        int port = change.diPort();
        boolean active = change.active();
        if (port == config.idlePort()) {
            if (config.idleOnEnabled()) {
                diHandlers.handleIdlePort(active);
            }
        } else if (port == config.triggerPort()) {
            diHandlers.handleTriggerPort(active);
        }
    }

    /** Первый usable wait_frame после DI3: гасим раньше {@code off_delay_ms}. */
    public void onFirstFrameCaptured(int cameraId) {
        if (!config.enabled() || !config.offOnFirstFrame() || lights.constantMode()) {
            return;
        }
        if (!awaitingFrameOff.compareAndSet(true, false)) {
            return;
        }
        cycle.cancelPendingOff();
        log.info("interval_flash Off on first frame cam={}", cameraId);
        cycle.executeOffOnFirstFrame();
    }

    /** Wiring compatibility — no-op (brightness via ApplyDirectOn). */
    public void onBrightnessUpdated() {
    }

    /** Уже в «холостом» уровне при первом сэмпле порта (без фронта). */
    static boolean isIdleLevel(boolean active, TriggerEdgeMode edge) {
        return edge == TriggerEdgeMode.FALLING ? !active : active;
    }

    static boolean isEdge(boolean previous, boolean current, TriggerEdgeMode edge) {
        return edge == TriggerEdgeMode.FALLING ? previous && !current : !previous && current;
    }

    public boolean lightsOn() {
        return lightsOn.get();
    }

    /** Тесты: дождаться опустошения очереди On/Off (включая delay=0). */
    void awaitLightTasks(long timeoutMs) throws LightingException {
        try {
            lightExecutor.submit(() -> null).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LightingException(e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new LightingException(e);
        }
    }

    @Override
    public void close() {
        cycle.cancelPendingOff();
        cycle.cancelPendingOn();
        cycle.clearCaptureFlags();
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
