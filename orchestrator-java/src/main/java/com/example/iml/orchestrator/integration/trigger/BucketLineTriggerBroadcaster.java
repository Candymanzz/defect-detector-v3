package com.example.iml.orchestrator.integration.trigger;

import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Периодическая line-broadcast рассылка на шину триггеров (для continuous/timer режимов с bucket aggregation).
 */
public final class BucketLineTriggerBroadcaster implements AutoCloseable {

    private final Logger log;
    private final InspectionTriggerBus bus;
    private final long intervalMs;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public BucketLineTriggerBroadcaster(Logger log, InspectionTriggerBus bus, long intervalMs) {
        this.log = log;
        this.bus = bus;
        this.intervalMs = Math.max(500L, intervalMs);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bucket-line-trigger");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleAtFixedRate(this::broadcastLineTrigger, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("bucket line trigger broadcaster started interval_ms={}", intervalMs);
    }

    private void broadcastLineTrigger() {
        try {
            int published = bus.publishBroadcast(InspectionTriggerEvent.lineBroadcast("bucket-line"));
            if (published > 0) {
                log.debug("bucket line trigger broadcast cameras={}", published);
            }
        } catch (RuntimeException e) {
            log.warn("bucket line trigger broadcast failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdownNow();
    }
}
