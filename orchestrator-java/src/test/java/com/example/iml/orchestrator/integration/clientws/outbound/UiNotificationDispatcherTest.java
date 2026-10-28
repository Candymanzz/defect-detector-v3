package com.example.iml.orchestrator.integration.clientws.outbound;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiNotificationDispatcherTest {

    @Test
    void stalledUiAndFullQueueNeverRunOnInspectionThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<Thread> uiThread = new AtomicReference<>();
        try (UiNotificationDispatcher dispatcher = new UiNotificationDispatcher(LogManager.getLogger(getClass()), 1)) {
            try {
                dispatcher.dispatch(() -> {
                    uiThread.set(Thread.currentThread());
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                dispatcher.dispatch(delivered::countDown);
                // This notification cannot fit. Running it on the producer would stall FINS.
                dispatcher.dispatch(() -> { throw new AssertionError("must not run on producer"); });
                assertEquals(1, dispatcher.droppedCount());
                assertEquals(1, delivered.getCount());
                assertNotEquals(Thread.currentThread(), uiThread.get());
            } finally {
                release.countDown();
            }
            assertTrue(delivered.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedNotificationDoesNotStopLaterResults() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        try (UiNotificationDispatcher dispatcher = new UiNotificationDispatcher(LogManager.getLogger(getClass()), 4)) {
            dispatcher.dispatch(() -> { throw new IllegalStateException("disconnected UI"); });
            dispatcher.dispatch(delivered::countDown);
            assertTrue(delivered.await(1, TimeUnit.SECONDS));
        }
    }
}
