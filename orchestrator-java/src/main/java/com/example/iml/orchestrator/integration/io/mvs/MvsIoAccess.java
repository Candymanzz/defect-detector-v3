package com.example.iml.orchestrator.integration.io.mvs;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** One SDK handle/selector, with output requests ahead of the next input sample. */
final class MvsIoAccess {
    interface Guard extends AutoCloseable { @Override void close(); }
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition outputsFinished = lock.newCondition();
    private final AtomicInteger pendingOutputs = new AtomicInteger();

    Guard read() {
        lock.lock();
        // Nested startup/diagnostic calls already own the SDK and must not wait on themselves.
        while (lock.getHoldCount() == 1 && pendingOutputs.get() > 0) outputsFinished.awaitUninterruptibly();
        return lock::unlock;
    }

    Guard write() {
        pendingOutputs.incrementAndGet();
        lock.lock();
        pendingOutputs.decrementAndGet();
        return () -> {
            outputsFinished.signalAll();
            lock.unlock();
        };
    }
}
