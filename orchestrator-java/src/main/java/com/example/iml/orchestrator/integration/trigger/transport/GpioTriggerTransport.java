package com.example.iml.orchestrator.integration.trigger.transport;

import com.example.iml.orchestrator.integration.gpio.DiscreteInputSnapshot;
import com.example.iml.orchestrator.integration.gpio.DiscreteInputSnapshotSource;
import com.example.iml.orchestrator.integration.gpio.DiscreteInputSnapshotSources;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.config.GpioTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.gpio.LineDiscreteTriggerEvaluator;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Опрос дискретных входов (Работа / Направление / Триггер) и публикация line-broadcast.
 */
public final class GpioTriggerTransport implements TriggerTransport {

    private final Logger log;
    private final GpioTriggerConfig config;
    private final InspectionTriggerBus bus;
    private final Runnable onLineWorkChanged;
    private final LineDiscreteTriggerEvaluator evaluator;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean lineWorkActive = new AtomicBoolean(false);

    private DiscreteInputSnapshotSource snapshotSource;
    private Thread pollThread;
    private long lastFireMs;
    private boolean lastLoggedTriggerActive;
    private boolean diStateInitialized;

    public GpioTriggerTransport(
            Logger log,
            GpioTriggerConfig config,
            InspectionTriggerBus bus,
            Runnable onLineWorkChanged
    ) {
        this.log = log;
        this.config = config;
        this.bus = bus;
        this.onLineWorkChanged = onLineWorkChanged == null ? () -> { } : onLineWorkChanged;
        this.evaluator = new LineDiscreteTriggerEvaluator(config.requireWork(), config.requireDirection());
    }

    public boolean isLineWorkActive() {
        return lineWorkActive.get();
    }

    @Override
    public void start() {
        if (!config.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        if (!config.fullyConfigured()) {
            running.set(false);
            throw new IllegalStateException(
                    "inspection_trigger.gpio enabled but backend is not configured (hikrobot_mv_io com_port or sysfs paths)"
            );
        }
        snapshotSource = DiscreteInputSnapshotSources.create(config);
        pollThread = new Thread(this::pollLoop, "discrete-trigger");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void pollLoop() {
        log.info(
                "discrete_trigger started backend={} poll_ms={} debounce_ms={} active={} com_port={} di={}/{}/{} require_work={} require_direction={}",
                config.backend(),
                config.pollIntervalMs(),
                config.debounceMs(),
                config.activeValue(),
                config.comPort(),
                config.workPort(),
                config.directionPort(),
                config.triggerPort(),
                config.requireWork(),
                config.requireDirection()
        );
        try {
            DiscreteInputSnapshot initial = snapshotSource.readSnapshot();
            evaluator.armTriggerState(initial.trigger());
            logDiStateIfChanged(initial);
            log.info(
                    "discrete_trigger armed DI{} level={} (no capture until 0→1 rising edge)",
                    config.triggerPort(),
                    initial.trigger() ? 1 : 0
            );
        } catch (Exception e) {
            log.warn("discrete_trigger arm failed: {}", e.getMessage());
        }
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                pollOnce();
                Thread.sleep(config.pollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("discrete_trigger poll error: {}", e.getMessage());
                sleepQuietly(Math.max(config.pollIntervalMs(), 50));
            }
        }
        log.info("discrete_trigger stopped");
    }

    private void pollOnce() throws Exception {
        DiscreteInputSnapshot snapshot = snapshotSource.readSnapshot();
        logDiStateIfChanged(snapshot);
        updateLineWork(snapshot.work());
        LineDiscreteTriggerEvaluator.Decision decision = evaluator.evaluate(
                snapshot.work(),
                snapshot.direction(),
                snapshot.trigger()
        );
        switch (decision) {
            case NONE -> { }
            case SKIP_NOT_READY -> log.info(
                    "discrete_trigger skip rising edge on DI{}: work=0 (need DI{}=1)",
                    config.triggerPort(),
                    config.workPort()
            );
            case SKIP_WRONG_DIRECTION -> log.info(
                    "discrete_trigger skip rising edge on DI{}: direction=0 (need DI{}=1)",
                    config.triggerPort(),
                    config.directionPort()
            );
            case FIRE -> publishDebounced();
        }
    }

    private void logDiStateIfChanged(DiscreteInputSnapshot snapshot) {
        if (!diStateInitialized || snapshot.trigger() != lastLoggedTriggerActive) {
            diStateInitialized = true;
            lastLoggedTriggerActive = snapshot.trigger();
            log.info(
                    "discrete_trigger di work={} direction={} trigger={} (ports {}/{}/{})",
                    snapshot.work() ? 1 : 0,
                    snapshot.direction() ? 1 : 0,
                    snapshot.trigger() ? 1 : 0,
                    config.workPort(),
                    config.directionPort(),
                    config.triggerPort()
            );
        }
    }

    private void updateLineWork(boolean work) {
        boolean previous = lineWorkActive.getAndSet(work);
        if (previous != work) {
            log.info("discrete_trigger line work {} -> {}", previous ? 1 : 0, work ? 1 : 0);
            onLineWorkChanged.run();
        }
    }

    private void publishDebounced() {
        if (config.debounceMs() > 0) {
            long now = System.currentTimeMillis();
            if (now - lastFireMs < config.debounceMs()) {
                log.debug("discrete_trigger debounced");
                return;
            }
            lastFireMs = now;
        }
        int published = bus.publishBroadcast(InspectionTriggerEvent.lineBroadcast("discrete"));
        if (published > 0) {
            log.info(
                    "discrete_trigger FIRE DI{} rising edge -> line broadcast cameras={}",
                    config.triggerPort(),
                    published
            );
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (pollThread != null) {
            pollThread.interrupt();
            try {
                pollThread.join(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (snapshotSource != null) {
            try {
                snapshotSource.close();
            } catch (Exception ignored) {
            }
            snapshotSource = null;
        }
    }
}
