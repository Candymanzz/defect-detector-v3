package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.trigger.api.LineTriggerListener;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Шина внешних триггеров: UDP и другие транспорты публикуют сюда, пайплайн камеры — читает.
 */
public final class InspectionTriggerBus implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(InspectionTriggerBus.class);

    private final Map<Integer, BlockingQueue<InspectionTriggerEvent>> perCamera = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);
    private final int captureTriggerStaggerMs;
    private final ScheduledExecutorService staggerScheduler;
    private final InspectionTriggerDispatch dispatch;
    private volatile LineTriggerListener lineTriggerListener;

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
        }
        this.dispatch = new InspectionTriggerDispatch(
                perCamera, this.captureTriggerStaggerMs, this.staggerScheduler, LOG);
    }

    public boolean hasCamera(int cameraId) {
        return perCamera.containsKey(cameraId);
    }

    public void setLineTriggerListener(LineTriggerListener lineTriggerListener) {
        this.lineTriggerListener = lineTriggerListener;
    }

    /** Публикует событие; broadcast — во все очереди; неизвестная камера — false. */
    public boolean publish(InspectionTriggerEvent raw) {
        if (raw.broadcast()) {
            return publishBroadcast(raw) > 0;
        }
        return dispatch.offerToCamera(raw.cameraId(), raw.receivedAt(), raw.source(), sequence.incrementAndGet());
    }

    public long prefireLineBroadcast(String source) {
        return prefireLineBroadcast(source, null);
    }

    public long prefireLineBroadcast(String source, List<Integer> cameraIds) {
        long seq = sequence.incrementAndGet();
        Instant receivedAt = Instant.now();
        LineTriggerListener listener = lineTriggerListener;
        if (listener != null) {
            listener.onLineTrigger(seq, receivedAt, cameraIds);
        }
        LOG.info(
                "sync_diag channel=inspect event=line_prefire_reserved trigger_sequence={} source={} cameras={}",
                seq,
                source,
                cameraIds == null || cameraIds.isEmpty() ? "all" : cameraIds.size()
        );
        return seq;
    }

    public int dispatchLineBroadcast(String source, long seq) {
        if (seq <= 0L) {
            return 0;
        }
        Instant receivedAt = Instant.now();
        return dispatch.dispatchLineBroadcast(source, seq, receivedAt, null);
    }

    public int dispatchLineBroadcast(String source, long seq, List<Integer> cameraIds) {
        if (seq <= 0L) {
            return 0;
        }
        Instant receivedAt = Instant.now();
        return dispatch.dispatchLineBroadcast(source, seq, receivedAt, cameraIds);
    }

    /** Рассылка триггера инспекции без prefire (экспозиция уже на Line0 через IoInputMonitor→DO0). */
    public int dispatchLineBroadcastWithoutPrefire(String source, List<Integer> cameraIds) {
        long seq = sequence.incrementAndGet();
        Instant receivedAt = Instant.now();
        LOG.info(
                "sync_diag channel=inspect event=line_dispatch_only trigger_sequence={} source={} cameras={}",
                seq,
                source,
                cameraIds == null || cameraIds.isEmpty() ? "all" : cameraIds.size()
        );
        return dispatch.dispatchLineBroadcast(source, seq, receivedAt, cameraIds);
    }

    /** Рассылка одного триггера на все активные камеры (prefire + dispatch в одном шаге). */
    public int publishBroadcast(InspectionTriggerEvent raw) {
        return publishBroadcast(raw, null);
    }

    public int publishBroadcast(InspectionTriggerEvent raw, List<Integer> cameraIds) {
        long seq = prefireLineBroadcast(raw.source(), cameraIds);
        Instant receivedAt = raw.receivedAt() == null ? Instant.now() : raw.receivedAt();
        return dispatch.dispatchLineBroadcast(raw.source(), seq, receivedAt, cameraIds);
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
