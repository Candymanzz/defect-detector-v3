package com.example.iml.orchestrator.integration.ui.artifacts;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-camera publish coalescing: sequence tracking, queue discard, locks.
 */
public final class UiPublishScheduler {

    private final LongAdder droppedUiPublishTasks = new LongAdder();
    private final AtomicLong uiPublishSequence = new AtomicLong();
    private final ConcurrentHashMap<Integer, Long> latestUiPublishByCamera = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Object> uiPublishLockByCamera = new ConcurrentHashMap<>();

    public long nextSequence(int cameraId) {
        long publishSequence = uiPublishSequence.incrementAndGet();
        latestUiPublishByCamera.put(cameraId, publishSequence);
        return publishSequence;
    }

    public boolean isLatestPublish(int cameraId, long publishSequence) {
        return Long.valueOf(publishSequence).equals(latestUiPublishByCamera.get(cameraId));
    }

    public Object lockForCamera(int cameraId) {
        return uiPublishLockByCamera.computeIfAbsent(cameraId, ignored -> new Object());
    }

    public void removeQueuedPublishForCamera(ExecutorService executor, int cameraId) {
        if (!(executor instanceof ThreadPoolExecutor pool)) {
            return;
        }
        for (Runnable queued : pool.getQueue()) {
            if (queued instanceof UiPublishTask task
                    && task.cameraId() == cameraId
                    && pool.remove(queued)) {
                task.discard();
            }
        }
    }

    public long markRejected() {
        droppedUiPublishTasks.increment();
        return droppedUiPublishTasks.sum();
    }
}
