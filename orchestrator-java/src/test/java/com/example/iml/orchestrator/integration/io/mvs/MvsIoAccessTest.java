package com.example.iml.orchestrator.integration.io.mvs;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class MvsIoAccessTest {
    @Test void pendingOutputRunsBeforeNextQueuedInputAndStartupCallsCanReenter() throws Exception {
        var access = new MvsIoAccess();
        List<String> order = new CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> { try (var ignored = access.read()) { order.add("DI4"); } });
        Thread writer = new Thread(() -> { try (var ignored = access.write()) { order.add("DO3+DO4"); } });
        try (var ignored = access.read()) {
            order.add("DI2");
            reader.start(); parked(reader);
            writer.start(); parked(writer);
            // A nested SDK helper must not block while a writer is queued behind its caller.
            try (var nested = access.read()) { order.add("nested"); }
        }
        reader.join(2000); writer.join(2000);
        assertFalse(reader.isAlive()); assertFalse(writer.isAlive());
        assertEquals(List.of("DI2", "nested", "DO3+DO4", "DI4"), order);
    }
    private static void parked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1);
        assertEquals(Thread.State.WAITING, thread.getState());
    }
    @Test void failedOutputReleasesSdkForSubsequentInput() {
        var access = new MvsIoAccess();
        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = access.write()) { throw new IllegalStateException("SDK write failed"); }
        });
        try (var ignored = access.read()) { /* must not deadlock */ }
    }
}
