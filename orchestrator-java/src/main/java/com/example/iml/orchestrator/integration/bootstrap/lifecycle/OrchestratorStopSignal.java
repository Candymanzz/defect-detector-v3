package com.example.iml.orchestrator.integration.bootstrap.lifecycle;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Сигнал остановки оркестратора (например, выход процесса frontend).
 * Разблокирует {@code runCameraTasks} → {@code IntegrationBootstrap.finally} → shutdown + vision_ready=0.
 */
public final class OrchestratorStopSignal {

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicReference<String> reason = new AtomicReference<>();

    public void request(String why) {
        reason.compareAndSet(null, why == null || why.isBlank() ? "stop" : why.trim());
        latch.countDown();
    }

    public boolean isRequested() {
        return latch.getCount() == 0;
    }

    public String reason() {
        return reason.get();
    }

    public void await() throws InterruptedException {
        latch.await();
    }

    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return latch.await(timeout, unit);
    }
}
