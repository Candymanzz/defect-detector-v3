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
    private final LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean lineWorkActive = new AtomicBoolean(false);

    private DiscreteInputSnapshotSource snapshotSource;
    private Thread pollThread;
    private long lastFireMs;

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
                "discrete_trigger started backend={} poll_ms={} debounce_ms={} active={} com_port={} di={}/{}/{}",
                config.backend(),
                config.pollIntervalMs(),
                config.debounceMs(),
                config.activeValue(),
                config.comPort(),
                config.workPort(),
                config.directionPort(),
                config.triggerPort()
        );
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
        updateLineWork(snapshot.work());
        LineDiscreteTriggerEvaluator.Decision decision = evaluator.evaluate(
                snapshot.work(),
                snapshot.direction(),
                snapshot.trigger()
        );
        switch (decision) {
            case NONE -> { }
            case SKIP_NOT_READY -> log.debug("discrete_trigger skip: conveyor not running (work=0)");
            case SKIP_WRONG_DIRECTION -> log.debug("discrete_trigger skip: direction=0 (no capture this way)");
            case FIRE -> publishDebounced();
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
            log.info("discrete_trigger line broadcast cameras={}", published);
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
