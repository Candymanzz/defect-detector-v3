package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.logging.TriggerVerdictLog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
import java.util.function.BooleanSupplier;

/**
 * Шина внешних триггеров: UDP и другие транспорты публикуют сюда, пайплайн камеры — читает.
 */
public final class InspectionTriggerBus implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(InspectionTriggerBus.class);

    private final Map<Integer, BlockingQueue<InspectionTriggerEvent>> perCamera = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);
    private final AtomicLong lastDispatchedSequence = new AtomicLong(0);
    private final int captureTriggerStaggerMs;
    private final ScheduledExecutorService staggerScheduler;
    private final TriggerVerdictLog verdictLog;
    private volatile LineTriggerListener lineTriggerListener;
    private volatile BooleanSupplier dispatchAllowed = () -> true;

    public InspectionTriggerBus(Collection<Integer> cameraIds) {
        this(cameraIds, 0);
    }

    public InspectionTriggerBus(Collection<Integer> cameraIds, int captureTriggerStaggerMs) {
        this(cameraIds, captureTriggerStaggerMs, null);
    }

    public InspectionTriggerBus(Collection<Integer> cameraIds, int captureTriggerStaggerMs,
                                TriggerVerdictLog verdictLog) {
        this.captureTriggerStaggerMs = Math.max(0, captureTriggerStaggerMs);
        this.verdictLog = verdictLog;
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
    }

    public boolean hasCamera(int cameraId) {
        return perCamera.containsKey(cameraId);
    }

    public void setLineTriggerListener(LineTriggerListener lineTriggerListener) {
        this.lineTriggerListener = lineTriggerListener;
    }

    /**
     * Гейт line-dispatch: при {@code false} новые триггеры не попадают в очереди камер
     * (например, пока analis_surface или geometry нездоровы; io_input_monitor не блокирует).
     */
    public void setDispatchAllowed(BooleanSupplier dispatchAllowed) {
        this.dispatchAllowed = dispatchAllowed == null ? () -> true : dispatchAllowed;
    }

    /** Сбрасывает накопленные, но ещё не обработанные триггеры. */
    public int clearAllPending() {
        int cleared = 0;
        for (BlockingQueue<InspectionTriggerEvent> queue : perCamera.values()) {
            if (queue == null) {
                continue;
            }
            cleared += queue.size();
            queue.clear();
        }
        return cleared;
    }

    /** Публикует событие; broadcast — во все очереди; неизвестная камера — false. */
    public boolean publish(InspectionTriggerEvent raw) {
        if (raw.broadcast()) {
            return publishBroadcast(raw) > 0;
        }
        long seq = sequence.incrementAndGet();
        if (verdictLog != null) {
            verdictLog.triggerReceived(seq, raw.receivedAt(), raw.receivedNanos(), raw.source(), List.of(raw.cameraId()));
        }
        boolean accepted = offerToCamera(raw.cameraId(), raw.receivedAt(), raw.source(), seq);
        if (!accepted && verdictLog != null) {
            verdictLog.triggerSkipped(seq, "camera_queue_unavailable_or_full");
        }
        return accepted;
    }

    public long prefireLineBroadcast(String source) {
        return prefireLineBroadcast(source, null);
    }

    public long prefireLineBroadcast(String source, List<Integer> cameraIds) {
        return prefireLineBroadcast(source, cameraIds, Instant.now(), System.nanoTime());
    }

    public long prefireLineBroadcast(String source, List<Integer> cameraIds,
                                    Instant receivedAt, long receivedNanos) {
        long seq = sequence.incrementAndGet();
        Instant acceptedAt = receivedAt == null ? Instant.now() : receivedAt;
        if (verdictLog != null) {
            verdictLog.triggerReceived(seq, acceptedAt, receivedNanos, source, resolveTargetCameras(cameraIds));
        }
        LineTriggerListener listener = lineTriggerListener;
        if (listener != null) {
            listener.onLineTrigger(seq, acceptedAt, cameraIds);
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
        return dispatchLineBroadcast(source, seq, receivedAt, null);
    }

    public int dispatchLineBroadcast(String source, long seq, List<Integer> cameraIds) {
        if (seq <= 0L) {
            return 0;
        }
        Instant receivedAt = Instant.now();
        return dispatchLineBroadcast(source, seq, receivedAt, cameraIds);
    }

    /** Рассылка триггера инспекции без prefire (экспозиция уже на Line0 через IoInputMonitor→DO0). */
    public int dispatchLineBroadcastWithoutPrefire(String source, List<Integer> cameraIds) {
        return dispatchLineBroadcastWithoutPrefire(source, cameraIds, Instant.now(), System.nanoTime());
    }

    public int dispatchLineBroadcastWithoutPrefire(String source, List<Integer> cameraIds,
                                                   Instant receivedAt, long receivedNanos) {
        long seq = sequence.incrementAndGet();
        Instant acceptedAt = receivedAt == null ? Instant.now() : receivedAt;
        if (verdictLog != null) {
            verdictLog.triggerReceived(seq, acceptedAt, receivedNanos, source, resolveTargetCameras(cameraIds));
        }
        LOG.info(
                "sync_diag channel=inspect event=line_dispatch_only trigger_sequence={} source={} cameras={}",
                seq,
                source,
                cameraIds == null || cameraIds.isEmpty() ? "all" : cameraIds.size()
        );
        return dispatchLineBroadcast(source, seq, acceptedAt, cameraIds);
    }

    /** Рассылка одного триггера на все активные камеры (prefire + dispatch в одном шаге). */
    public int publishBroadcast(InspectionTriggerEvent raw) {
        return publishBroadcast(raw, null);
    }

    public int publishBroadcast(InspectionTriggerEvent raw, List<Integer> cameraIds) {
        Instant receivedAt = raw.receivedAt() == null ? Instant.now() : raw.receivedAt();
        long seq = prefireLineBroadcast(raw.source(), cameraIds, receivedAt, raw.receivedNanos());
        return dispatchLineBroadcast(raw.source(), seq, receivedAt, cameraIds);
    }

    public long lastDispatchedSequence() {
        return lastDispatchedSequence.get();
    }

    /** Последовательность включает уже зарезервированный prefire, даже если dispatch ещё не завершён. */
    public long currentSequence() {
        return sequence.get();
    }

    /**
     * Вклинить камеру в уже идущий цикл (Stop→Start): очищает очередь и кладёт событие с нужным seq.
     */
    public boolean injectSequence(int cameraId, long seq, String source) {
        if (seq <= 0L || !perCamera.containsKey(cameraId)) {
            return false;
        }
        BlockingQueue<InspectionTriggerEvent> queue = perCamera.get(cameraId);
        if (queue == null) {
            return false;
        }
        queue.clear();
        return queue.offer(new InspectionTriggerEvent(
                cameraId,
                seq,
                Instant.now(),
                source == null || source.isBlank() ? "rejoin" : source,
                false
        ));
    }

    private int dispatchLineBroadcast(String source, long seq, Instant receivedAt, List<Integer> cameraIds) {
        if (!dispatchAllowed.getAsBoolean()) {
            LOG.warn(
                    "sync_diag channel=inspect event=line_dispatch_skipped trigger_sequence={} source={} reason=services_unhealthy",
                    seq,
                    source
            );
            if (verdictLog != null) {
                verdictLog.triggerSkipped(seq, "services_unhealthy");
            }
            return 0;
        }
        List<Integer> targets = resolveTargetCameras(cameraIds);
        if (targets.isEmpty()) {
            if (verdictLog != null) {
                verdictLog.triggerSkipped(seq, "no_target_cameras");
            }
            return 0;
        }
        lastDispatchedSequence.set(seq);
        if (captureTriggerStaggerMs <= 0 || staggerScheduler == null) {
            LOG.info(
                    "sync_diag channel=inspect event=line_dispatch trigger_sequence={} cameras={} stagger_ms=0 mode=simultaneous",
                    seq,
                    targets.size()
            );
            int published = 0;
            for (Integer cameraId : targets) {
                if (offerToCamera(cameraId, receivedAt, source, seq)) {
                    published++;
                }
            }
            if (published == 0 && verdictLog != null) {
                verdictLog.triggerSkipped(seq, "all_camera_queues_full");
            }
            return published;
        }
        LOG.info(
                "sync_diag channel=inspect event=line_dispatch trigger_sequence={} cameras={} stagger_ms={} mode=staggered",
                seq,
                targets.size(),
                captureTriggerStaggerMs
        );
        for (int i = 0; i < targets.size(); i++) {
            int cameraId = targets.get(i);
            long delayMs = (long) i * captureTriggerStaggerMs;
            staggerScheduler.schedule(
                    () -> offerToCamera(cameraId, receivedAt, source, seq),
                    delayMs,
                    TimeUnit.MILLISECONDS
            );
        }
        return targets.size();
    }

    private List<Integer> resolveTargetCameras(List<Integer> cameraIds) {
        if (cameraIds == null || cameraIds.isEmpty()) {
            List<Integer> all = new ArrayList<>(perCamera.keySet());
            Collections.sort(all);
            return all;
        }
        List<Integer> filtered = new ArrayList<>();
        for (Integer cameraId : cameraIds) {
            if (cameraId != null && perCamera.containsKey(cameraId)) {
                filtered.add(cameraId);
            }
        }
        filtered.sort(Integer::compareTo);
        return filtered;
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

    @Override
    public void close() {
        if (staggerScheduler != null) {
            staggerScheduler.shutdownNow();
        }
    }
}
