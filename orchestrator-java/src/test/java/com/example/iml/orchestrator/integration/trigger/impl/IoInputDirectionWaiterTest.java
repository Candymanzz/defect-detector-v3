package com.example.iml.orchestrator.integration.trigger.impl;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoInputDirectionWaiterTest {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private IoInputDirectionWaiter waiter;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (waiter != null) {
            waiter.close();
        }
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    void firesWhenDirectionBecomesReadyDuringWait() throws InterruptedException {
        AtomicBoolean ready = new AtomicBoolean(false);
        AtomicBoolean fired = new AtomicBoolean(false);
        waiter = new IoInputDirectionWaiter(
                LogManager.getLogger("test"),
                executor,
                500,
                20,
                ready::get,
                () -> true,
                () -> fired.set(true),
                () -> fired.set(false)
        );

        waiter.begin("test");
        Thread.sleep(80);
        ready.set(true);
        waiter.onDirectionReadyEvent();

        Thread.sleep(50);
        assertTrue(fired.get());
    }

    @Test
    void timesOutWhenDirectionNeverArrives() throws InterruptedException {
        AtomicBoolean fired = new AtomicBoolean(false);
        AtomicBoolean timedOut = new AtomicBoolean(false);
        waiter = new IoInputDirectionWaiter(
                LogManager.getLogger("test"),
                executor,
                120,
                20,
                () -> false,
                () -> true,
                () -> fired.set(true),
                () -> timedOut.set(true)
        );

        waiter.begin("test");
        Thread.sleep(250);

        assertFalse(fired.get());
        assertTrue(timedOut.get());
    }
}
