package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Окно съёмки: DI3/DI5 при DI2=1 открывают wait; DI2↓ даёт grace на GigE-доставку,
 * затем закрывает окно и отменяет хвост — reverse Line0 после grace не публикуем.
 */
public final class Di2CaptureWindow implements AutoCloseable {

    private final Logger log;
    private final PerCameraInspectionGate gate;
    private final int targetFrames;
    private final long timeoutMs;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final AtomicBoolean open = new AtomicBoolean(false);
    private final AtomicInteger received = new AtomicInteger(0);
    /** Unique camera/phase pairs. Duplicate callbacks must not complete the bundle early. */
    private final Set<FrameKey> receivedFrames = ConcurrentHashMap.newKeySet();
    private volatile ScheduledFuture<?> timeoutTask;
    private volatile ScheduledFuture<?> di2LowCloseTask;
    /** Пока DI2=1 — Long.MAX_VALUE; после DI2↓ — now+grace. */
    private final AtomicLong acceptUntilMs = new AtomicLong(Long.MAX_VALUE);

    public Di2CaptureWindow(
            Logger log,
            PerCameraInspectionGate gate,
            int cameraCount,
            int framesPerCamera,
            long timeoutMs
    ) {
        this(
                log,
                gate,
                cameraCount,
                framesPerCamera,
                timeoutMs,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "di2-capture-window");
                    t.setDaemon(true);
                    return t;
                }),
                true
        );
    }

    /** @param directionActive ignored — оставлен для совместимости вызовов bootstrap. */
    public Di2CaptureWindow(
            Logger log,
            PerCameraInspectionGate gate,
            int cameraCount,
            int framesPerCamera,
            long timeoutMs,
            java.util.function.BooleanSupplier directionActive
    ) {
        this(log, gate, cameraCount, framesPerCamera, timeoutMs);
    }

    Di2CaptureWindow(
            Logger log,
            PerCameraInspectionGate gate,
            int cameraCount,
            int framesPerCamera,
            long timeoutMs,
            ScheduledExecutorService scheduler,
            boolean ownsScheduler
    ) {
        this.log = Objects.requireNonNull(log, "log");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.targetFrames = Math.max(1, cameraCount) * Math.max(1, framesPerCamera);
        this.timeoutMs = Math.max(100L, timeoutMs);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    public int targetFrames() {
        return targetFrames;
    }

    public boolean isOpen() {
        return open.get();
    }

    /**
     * @return true если окно только что открыто; false если уже открыто (повторный DI2↑ игнор).
     */
    public boolean tryOpen() {
        if (!open.compareAndSet(false, true)) {
            log.info(
                    "di2_window skip open — already collecting frames={}/{}",
                    received.get(),
                    targetFrames
            );
            return false;
        }
        received.set(0);
        receivedFrames.clear();
        acceptUntilMs.set(Long.MAX_VALUE);
        cancelTimeout();
        cancelDi2LowClose();
        timeoutTask = scheduler.schedule(() -> close("timeout"), timeoutMs, TimeUnit.MILLISECONDS);
        log.info("di2_window open target_frames={} timeout_ms={}", targetFrames, timeoutMs);
        return true;
    }

    /**
     * DI2↓: ещё {@code graceMs} принимаем кадры от импульса (GigE), потом close+cancel.
     * Новые DI3/DI5 при DI2=0 софт уже не армит.
     */
    public void scheduleCloseAfterDi2Low(long graceMs) {
        if (!open.get()) {
            return;
        }
        long grace = Math.max(50L, Math.min(2000L, graceMs));
        long until = System.currentTimeMillis() + grace;
        acceptUntilMs.set(until);
        cancelDi2LowClose();
        di2LowCloseTask = scheduler.schedule(() -> close("di2_low"), grace, TimeUnit.MILLISECONDS);
        log.info("di2_window DI2↓ — accept frames until +{} ms, then cancel leftovers", grace);
    }

    public void onCaptureOk(int cameraId, int phaseId, long parentCycleId) {
        if (!open.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now > acceptUntilMs.get()) {
            log.info(
                    "di2_window drop late frame cam={} phase={} parent_cycle={} — after DI2 grace",
                    cameraId,
                    phaseId,
                    parentCycleId
            );
            return;
        }
        FrameKey key = new FrameKey(cameraId, phaseId);
        if (!receivedFrames.add(key)) {
            log.warn(
                    "di2_window duplicate frame ignored cam={} phase={} parent_cycle={} frames={}/{}",
                    cameraId,
                    phaseId,
                    parentCycleId,
                    received.get(),
                    targetFrames
            );
            return;
        }
        int n = received.incrementAndGet();
        log.info(
                "di2_window frames={}/{} cam={} phase={} parent_cycle={}",
                n,
                targetFrames,
                cameraId,
                phaseId,
                parentCycleId
        );
        if (n >= targetFrames) {
            close("complete");
        }
    }

    public void close(String reason) {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        acceptUntilMs.set(0L);
        cancelTimeout();
        cancelDi2LowClose();
        int frames = received.get();
        // Reaching the full frame count means every capture already succeeded.  Do not
        // mark those still-finishing pipeline tasks as cancelled: the final task calls
        // onCaptureOk just before it records its bucket/UI result, so cancelling here
        // used to suppress exactly that twentieth result (one 4/5 bucket per phase).
        Set<Integer> cancelled = "complete".equals(reason)
                ? Set.of()
                : gate.requestCancelAllInFlight(false);
        log.info(
                "di2_window close reason={} frames={}/{} leftover_cancel={}",
                reason == null ? "unknown" : reason,
                frames,
                targetFrames,
                cancelled
        );
    }

    private void cancelTimeout() {
        ScheduledFuture<?> task = timeoutTask;
        timeoutTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private void cancelDi2LowClose() {
        ScheduledFuture<?> task = di2LowCloseTask;
        di2LowCloseTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    @Override
    public void close() {
        close("shutdown");
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private record FrameKey(int cameraId, int phaseId) {
    }
}
