package com.example.iml.orchestrator.integration.clientws.outbound;

import org.apache.logging.log4j.Logger;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/** Keeps UI serialization, sockets and logging off inspection and FINS threads. */
public final class UiNotificationDispatcher implements AutoCloseable {

    private final Logger log;
    private final ThreadPoolExecutor executor;
    private final LongAdder dropped = new LongAdder();

    public UiNotificationDispatcher(Logger log, int queueCapacity) {
        this.log = log;
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)), r -> {
                    Thread thread = new Thread(r, "client-ws-notify");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public void dispatch(Runnable notification) {
        try {
            executor.execute(() -> {
                try {
                    notification.run();
                } catch (RuntimeException e) {
                    log.debug("ui notification failed: {}", e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            // A stalled UI must never run work on, or block, the caller (especially FINS).
            dropped.increment();
        }
    }

    public long droppedCount() {
        return dropped.sum();
    }

    @Override
    public void close() {
        executor.shutdownNow();
        if (dropped.sum() > 0L) {
            log.info("ui notifications dropped={} (bounded queue; inspection/FINS were not blocked)", dropped.sum());
        }
    }
}
