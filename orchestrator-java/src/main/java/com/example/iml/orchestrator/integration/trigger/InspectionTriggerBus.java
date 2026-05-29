package com.example.iml.orchestrator.integration.trigger;

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

    /** Публикует событие; неизвестная камера — false. */
    public boolean publish(InspectionTriggerEvent raw) {
        BlockingQueue<InspectionTriggerEvent> queue = perCamera.get(raw.cameraId());
        if (queue == null) {
            return false;
        }
        InspectionTriggerEvent event = new InspectionTriggerEvent(
                raw.cameraId(),
                sequence.incrementAndGet(),
                raw.receivedAt(),
                raw.source()
        );
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
