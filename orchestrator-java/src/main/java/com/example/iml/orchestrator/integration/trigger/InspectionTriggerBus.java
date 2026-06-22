package com.example.iml.orchestrator.integration.trigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Шина внешних триггеров: UDP и другие транспорты публикуют сюда, пайплайн камеры — читает.
 */
public final class InspectionTriggerBus implements AutoCloseable {

    private final Map<Integer, BlockingQueue<InspectionTriggerEvent>> perCamera = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicLong> discardThroughSequenceByCamera = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);
    private final int captureTriggerStaggerMs;
    private final ScheduledExecutorService staggerScheduler;

    public InspectionTriggerBus(Collection<Integer> cameraIds) {
        this(cameraIds, 0);
    }

    public InspectionTriggerBus(Collection<Integer> cameraIds, int captureTriggerStaggerMs) {
        this.captureTriggerStaggerMs = Math.max(0, captureTriggerStaggerMs);
        this.staggerScheduler = this.captureTriggerStaggerMs > 0
                ? Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "inspection-trigger-stagger");
                    t.setDaemon(true);
                    return t;
                })
                : null;
        for (int cameraId : cameraIds) {
            perCamera.put(cameraId, new LinkedBlockingQueue<>(512));
            discardThroughSequenceByCamera.put(cameraId, new AtomicLong(0L));
        }
    }

    public boolean hasCamera(int cameraId) {
        return perCamera.containsKey(cameraId);
    }

    /** Публикует событие; broadcast — во все очереди; неизвестная камера — false. */
    public boolean publish(InspectionTriggerEvent raw) {
        if (raw.broadcast()) {
            return publishBroadcast(raw) > 0;
        }
        return offerToCamera(raw.cameraId(), raw.receivedAt(), raw.source(), sequence.incrementAndGet());
    }

    /** Рассылка одного триггера на все активные камеры (общая {@code sequence}). */
    public int publishBroadcast(InspectionTriggerEvent raw) {
        long seq = sequence.incrementAndGet();
        Instant receivedAt = raw.receivedAt() == null ? java.time.Instant.now() : raw.receivedAt();
        List<Integer> cameraIds = new ArrayList<>(perCamera.keySet());
        Collections.sort(cameraIds);
        if (captureTriggerStaggerMs <= 0 || staggerScheduler == null) {
            int published = 0;
            for (Integer cameraId : cameraIds) {
                if (offerToCamera(cameraId, receivedAt, raw.source(), seq)) {
                    published++;
                }
            }
            return published;
        }
        for (int i = 0; i < cameraIds.size(); i++) {
            int cameraId = cameraIds.get(i);
            long delayMs = (long) i * captureTriggerStaggerMs;
            staggerScheduler.schedule(
                    () -> offerToCamera(cameraId, receivedAt, raw.source(), seq),
                    delayMs,
                    TimeUnit.MILLISECONDS
            );
        }
        return cameraIds.size();
    }

    private boolean offerToCamera(int cameraId, Instant receivedAt, String source, long seq) {
        BlockingQueue<InspectionTriggerEvent> queue = perCamera.get(cameraId);
        if (queue == null) {
            return false;
        }
        AtomicLong discardThrough = discardThroughSequenceByCamera.get(cameraId);
        if (discardThrough != null && seq <= discardThrough.get()) {
            return false;
        }
        InspectionTriggerEvent event = new InspectionTriggerEvent(cameraId, seq, receivedAt, source, false);
        return queue.offer(event);
    }

    /**
     * Drops all triggers that existed at the resume boundary, including staggered events
     * already scheduled with the current global sequence.
     */
    public long discardPendingThroughCurrentSequence(int cameraId) {
        BlockingQueue<InspectionTriggerEvent> queue = perCamera.get(cameraId);
        AtomicLong discardThrough = discardThroughSequenceByCamera.get(cameraId);
        if (queue == null || discardThrough == null) {
            return 0L;
        }
        long boundary = sequence.get();
        discardThrough.set(boundary);
        queue.clear();
        return boundary;
    }

    public InspectionTriggerEvent take(int cameraId) throws InterruptedException {
        BlockingQueue<InspectionTriggerEvent> queue = perCamera.get(cameraId);
        if (queue == null) {
            throw new IllegalArgumentException("unknown camera_id=" + cameraId);
        }
        return queue.take();
    }

    @Override
    public void close() {
        if (staggerScheduler != null) {
            staggerScheduler.shutdownNow();
        }
    }
}
