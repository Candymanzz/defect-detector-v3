package com.example.iml.orchestrator.integration.io.mvs;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class MvsSdkLeaseTest {
    @Test void ClosingLightsDoesNotFinalizeSdkWhileIoStillUsesIt() {
        AtomicInteger init = new AtomicInteger(), finish = new AtomicInteger();
        var io = MvsSdkLease.acquire("test-shared-sdk", init::incrementAndGet, finish::incrementAndGet);
        var light1 = MvsSdkLease.acquire("test-shared-sdk", init::incrementAndGet, finish::incrementAndGet);
        var light2 = MvsSdkLease.acquire("test-shared-sdk", init::incrementAndGet, finish::incrementAndGet);
        assertEquals(1, init.get()); light1.close(); light2.close(); light2.close();
        assertEquals(0, finish.get()); io.close(); io.close(); assertEquals(1, finish.get());
    }
    @Test void failedInitializationDoesNotLeakALease() {
        assertThrows(IllegalStateException.class, () -> MvsSdkLease.acquire("test-failed-sdk", () -> { throw new IllegalStateException(); }, () -> fail()));
        AtomicInteger finish = new AtomicInteger();
        try (var lease = MvsSdkLease.acquire("test-failed-sdk", () -> {}, finish::incrementAndGet)) { assertEquals(0, finish.get()); }
        assertEquals(1, finish.get());
    }
}
