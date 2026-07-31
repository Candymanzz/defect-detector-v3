package com.example.iml.orchestrator.integration.clientws;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Stops ping scheduler and underlying WebSocket server.
 */
final class ClientWsServerShutdown {

    private ClientWsServerShutdown() {
    }

    static void close(ScheduledExecutorService pingScheduler, IntConsumer stopWithTimeoutMs) {
        pingScheduler.shutdown();
        try {
            if (!pingScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                pingScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pingScheduler.shutdownNow();
        }
        stopWithTimeoutMs.accept(3000);
    }
}
