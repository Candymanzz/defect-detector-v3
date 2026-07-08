package com.example.iml.orchestrator.integration.trigger.transport;

import org.apache.logging.log4j.Logger;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Ожидание направления с периодическим опросом и таймаутом вместо мгновенного skip на фронте DI3.
 */
final class IoInputDirectionWaiter implements AutoCloseable {

    private final Logger log;
    private final ScheduledExecutorService executor;
    private final int waitMs;
    private final int pollMs;
    private final BooleanSupplier directionReady;
    private final BooleanSupplier workReady;
    private final Runnable onReady;
    private final Runnable onTimeout;
    private final AtomicBoolean waiting = new AtomicBoolean(false);
    private ScheduledFuture<?> pollFuture;
    private ScheduledFuture<?> timeoutFuture;

    IoInputDirectionWaiter(
            Logger log,
            ScheduledExecutorService executor,
            int waitMs,
            int pollMs,
            BooleanSupplier directionReady,
            BooleanSupplier workReady,
            Runnable onReady,
            Runnable onTimeout
    ) {
        this.log = log;
        this.executor = executor;
        this.waitMs = Math.max(0, waitMs);
        this.pollMs = Math.max(1, pollMs);
        this.directionReady = directionReady;
        this.workReady = workReady;
        this.onReady = onReady;
        this.onTimeout = onTimeout;
    }

    boolean isWaiting() {
        return waiting.get();
    }

    void begin(String reason) {
        if (waitMs <= 0) {
            onTimeout.run();
            return;
        }
        cancelTasks();
        if (!waiting.compareAndSet(false, true)) {
            cancelTasks();
            waiting.set(true);
        }
        log.info("io_input_trigger await DI2 direction up to {} ms, poll {} ms ({})", waitMs, pollMs, reason);
        pollFuture = executor.scheduleAtFixedRate(this::poll, pollMs, pollMs, TimeUnit.MILLISECONDS);
        timeoutFuture = executor.schedule(this::onTimeoutElapsed, waitMs, TimeUnit.MILLISECONDS);
    }

    void onDirectionReadyEvent() {
        if (!waiting.get()) {
            return;
        }
        if (directionReady.getAsBoolean() && workReady.getAsBoolean()) {
            complete(true, "direction event");
        }
    }

    void cancel(String reason) {
        if (!waiting.compareAndSet(true, false)) {
            return;
        }
        cancelTasks();
        log.info("io_input_trigger direction wait cancelled ({})", reason);
    }

    private void poll() {
        if (!waiting.get()) {
            return;
        }
        if (directionReady.getAsBoolean() && workReady.getAsBoolean()) {
            complete(true, "poll");
        }
    }

    private void onTimeoutElapsed() {
        if (!waiting.get()) {
            return;
        }
        if (directionReady.getAsBoolean() && workReady.getAsBoolean()) {
            complete(true, "timeout edge");
            return;
        }
        complete(false, "timeout");
    }

    private void complete(boolean fire, String source) {
        if (!waiting.compareAndSet(true, false)) {
            return;
        }
        cancelTasks();
        if (fire) {
            log.info("io_input_trigger DI2 direction ready ({})", source);
            onReady.run();
        } else {
            onTimeout.run();
        }
    }

    private void cancelTasks() {
        ScheduledFuture<?> poll = pollFuture;
        if (poll != null) {
            poll.cancel(false);
            pollFuture = null;
        }
        ScheduledFuture<?> timeout = timeoutFuture;
        if (timeout != null) {
            timeout.cancel(false);
            timeoutFuture = null;
        }
    }

    @Override
    public void close() {
        cancel("shutdown");
    }
}
