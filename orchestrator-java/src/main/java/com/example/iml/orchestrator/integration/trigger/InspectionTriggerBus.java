package com.example.iml.orchestrator.integration.trigger;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Шина внешних триггеров: UDP и другие транспорты публикуют сюда, пайплайн камеры — читает.
 */
public final class InspectionTriggerBus {

    private final Map<Integer, BlockingQueue<InspectionTriggerEvent>> perCamera = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    public InspectionTriggerBus(Collection<Integer> cameraIds) {
        for (int cameraId : cameraIds) {
            perCamera.put(cameraId, new LinkedBlockingQueue<>(512));
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
        int published = 0;
        for (Integer cameraId : perCamera.keySet()) {
            if (offerToCamera(cameraId, receivedAt, raw.source(), seq)) {
                published++;
            }
        }
        return published;
    }

    private boolean offerToCamera(int cameraId, Instant receivedAt, String source, long seq) {
        BlockingQueue<InspectionTriggerEvent> queue = perCamera.get(cameraId);
        if (queue == null) {
            return false;
        }
        InspectionTriggerEvent event = new InspectionTriggerEvent(cameraId, seq, receivedAt, source, false);
        return queue.offer(event);
    }

    public InspectionTriggerEvent take(int cameraId) throws InterruptedException {
        BlockingQueue<InspectionTriggerEvent> queue = perCamera.get(cameraId);
        if (queue == null) {
            throw new IllegalArgumentException("unknown camera_id=" + cameraId);
        }
        return queue.take();
    }
}
