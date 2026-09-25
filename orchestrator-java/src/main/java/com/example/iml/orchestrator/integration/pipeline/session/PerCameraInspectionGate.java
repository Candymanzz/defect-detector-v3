package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-camera inspection gate: optional disable without stopping capture.
 * Для two-phase на одном {@code parentCycleId} фаза N+1 блокируется, пока на камере идёт фаза N
 * (второй DI3 может прийти через ~80 ms, пока ещё тянется wait_frame первой пачки).
 */
public final class PerCameraInspectionGate {

    private static final Logger LOG = LogManager.getLogger(PerCameraInspectionGate.class);

    private record PhaseKey(long parentCycleId, int phaseId) {
    }

    public enum BeginResult {
        STARTED,
        DISABLED,
        IN_FLIGHT
    }

    private final ConcurrentHashMap<Integer, AtomicBoolean> inspectionEnabled = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicBoolean> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Set<PhaseKey>> inFlightPhases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicBoolean> cancelRequested = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicLong> inspectionSequence = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicLong> activeTriggerSequence = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicLong> resumeAfterTriggerSequence = new ConcurrentHashMap<>();
    /** When true, phase N+1 may start while phase N is still in-flight (DI2 window / dual Line0). */
    private volatile boolean awaitPriorPhase = true;

    private PerCameraInspectionGate(
            Map<Integer, AtomicBoolean> enabled,
            Map<Integer, AtomicBoolean> inFlight,
            Map<Integer, AtomicBoolean> cancelRequested,
            Map<Integer, AtomicLong> inspectionSequence,
            Map<Integer, AtomicLong> activeTriggerSequence
    ) {
        this.inspectionEnabled.putAll(enabled);
        this.inFlight.putAll(inFlight);
        this.cancelRequested.putAll(cancelRequested);
        this.inspectionSequence.putAll(inspectionSequence);
        this.activeTriggerSequence.putAll(activeTriggerSequence);
        activeTriggerSequence.forEach((cameraId, ignored) ->
                this.resumeAfterTriggerSequence.put(cameraId, new AtomicLong(0L)));
        activeTriggerSequence.forEach((cameraId, ignored) ->
                this.inFlightPhases.put(cameraId, ConcurrentHashMap.newKeySet()));
    }

    public static PerCameraInspectionGate fromCameras(List<Map<String, Object>> cameras) {
        ConcurrentHashMap<Integer, AtomicBoolean> enabled = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, AtomicBoolean> flight = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, AtomicLong> sequences = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, AtomicLong> activeSequences = new ConcurrentHashMap<>();
        if (cameras != null) {
            for (Map<String, Object> camera : cameras) {
                Object idObj = camera.get("id");
                if (!(idObj instanceof Number n)) {
                    continue;
                }
                int cameraId = n.intValue();
                enabled.put(cameraId, new AtomicBoolean(YamlScalars.toBool(camera.get("inspection_enabled"), true)));
                flight.put(cameraId, new AtomicBoolean(false));
                cancelled.put(cameraId, new AtomicBoolean(false));
                sequences.put(cameraId, new AtomicLong(0L));
                activeSequences.put(cameraId, new AtomicLong(0L));
            }
        }
        return new PerCameraInspectionGate(enabled, flight, cancelled, sequences, activeSequences);
    }

    public boolean isKnownCamera(int cameraId) {
        return inspectionEnabled.containsKey(cameraId);
    }

    /**
     * {@code false} — phase1 не ждёт конца phase0 (оба wait_frame могут стартовать в одном DI2-окне).
     */
    public void setAwaitPriorPhase(boolean awaitPriorPhase) {
        this.awaitPriorPhase = awaitPriorPhase;
    }

    public boolean awaitPriorPhase() {
        return awaitPriorPhase;
    }

    public Set<Integer> cameraIds() {
        return Set.copyOf(inspectionEnabled.keySet());
    }

    public boolean isInspectionEnabled(int cameraId) {
        AtomicBoolean flag = inspectionEnabled.get(cameraId);
        return flag != null && flag.get();
    }

    public boolean isInspectionInFlight(int cameraId) {
        AtomicBoolean flag = inFlight.get(cameraId);
        return flag != null && flag.get();
    }

    /** Любая камера в цикле инспекции — для паузы live_preview и разгрузки GigE. */
    public boolean hasAnyInspectionInFlight() {
        for (AtomicBoolean flag : inFlight.values()) {
            if (flag != null && flag.get()) {
                return true;
            }
        }
        return false;
    }

    /** Хотя бы на одной камере инспекция включена (кнопка Start). */
    public boolean hasAnyInspectionEnabled() {
        for (AtomicBoolean flag : inspectionEnabled.values()) {
            if (flag != null && flag.get()) {
                return true;
            }
        }
        return false;
    }

    public void setInspectionEnabled(int cameraId, boolean enabled) {
        AtomicBoolean flag = inspectionEnabled.get(cameraId);
        AtomicBoolean flight = inFlight.get(cameraId);
        if (flag != null && flight != null) {
            synchronized (flight) {
                flag.set(enabled);
                AtomicLong boundary = resumeAfterTriggerSequence.get(cameraId);
                if (boundary != null) {
                    boundary.set(0L);
                }
            }
        } else if (flag != null) {
            flag.set(enabled);
        }
    }

    /** Включает инспекцию только для событий, которые новее уже принятого на момент пуска триггера. */
    public void armInspectionAfter(int cameraId, long triggerSequence) {
        AtomicBoolean flag = inspectionEnabled.get(cameraId);
        AtomicBoolean flight = inFlight.get(cameraId);
        AtomicLong boundary = resumeAfterTriggerSequence.get(cameraId);
        if (flag == null || flight == null || boundary == null) {
            return;
        }
        synchronized (flight) {
            boundary.set(Math.max(0L, triggerSequence));
            flag.set(true);
        }
    }

    /** Оставляет инспекцию включённой только на указанных камерах (режим 5/10). */
    public void setInspectionEnabledOnlyFor(Collection<Integer> allowedCameraIds) {
        if (allowedCameraIds == null || allowedCameraIds.isEmpty()) {
            return;
        }
        Set<Integer> allowed = Set.copyOf(allowedCameraIds);
        for (Integer cameraId : cameraIds()) {
            setInspectionEnabled(cameraId, allowed.contains(cameraId));
        }
    }

    public boolean disableInspectionAndRequestCancel(int cameraId) {
        AtomicBoolean flag = inspectionEnabled.get(cameraId);
        AtomicBoolean flight = inFlight.get(cameraId);
        AtomicBoolean cancelFlag = cancelRequested.get(cameraId);
        if (flag == null || flight == null || cancelFlag == null) {
            return false;
        }
        synchronized (flight) {
            flag.set(false);
            if (!flight.get()) {
                return false;
            }
            cancelFlag.set(true);
            return true;
        }
    }

    /** Soft-stop всех камер: disable + cancel in-flight, capture/reference не трогаем. */
    public Set<Integer> disableAllAndRequestCancel() {
        Set<Integer> cancelled = new LinkedHashSet<>();
        for (Integer cameraId : cameraIds()) {
            if (disableInspectionAndRequestCancel(cameraId)) {
                cancelled.add(cameraId);
            }
        }
        return Set.copyOf(cancelled);
    }

    /**
     * Отмена in-flight wait_frame без disable: кадры после закрытия окна DI2 не публикуем/не архивируем.
     * Phase1+ не трогаем — второй Line0 часто приходит/добивается уже при DI2=0.
     */
    public Set<Integer> requestCancelAllInFlight() {
        return requestCancelAllInFlight(true);
    }

    /**
     * @param keepPhase1 if true, cameras with only phase≥1 in-flight are left running (DI2↓).
     */
    public Set<Integer> requestCancelAllInFlight(boolean keepPhase1) {
        Set<Integer> cancelled = new LinkedHashSet<>();
        for (Integer cameraId : cameraIds()) {
            if (requestCancel(cameraId, keepPhase1)) {
                cancelled.add(cameraId);
            }
        }
        return Set.copyOf(cancelled);
    }

    public BeginResult tryBeginInspection(int cameraId) {
        return tryBeginInspection(cameraId, 0L);
    }

    public BeginResult tryBeginInspection(int cameraId, long triggerSequence) {
        long parentCycleId = Math.max(0L, triggerSequence);
        return tryBeginInspection(cameraId, parentCycleId, 0, triggerSequence);
    }

    public BeginResult tryBeginInspection(
            int cameraId,
            long parentCycleId,
            int phaseId,
            long triggerSequence
    ) {
        AtomicBoolean flight = inFlight.get(cameraId);
        if (flight == null) {
            return BeginResult.DISABLED;
        }
        synchronized (flight) {
            if (!isInspectionEnabled(cameraId)) {
                return BeginResult.DISABLED;
            }
            AtomicLong boundary = resumeAfterTriggerSequence.get(cameraId);
            if (boundary != null && triggerSequence > 0L && triggerSequence <= boundary.get()) {
                return BeginResult.DISABLED;
            }
            Set<PhaseKey> phases = inFlightPhases.get(cameraId);
            PhaseKey phaseKey = new PhaseKey(Math.max(0L, parentCycleId), Math.max(0, phaseId));
            if (phases == null) {
                return BeginResult.IN_FLIGHT;
            }
            if (phases.contains(phaseKey)) {
                return BeginResult.IN_FLIGHT;
            }
            awaitPriorPhaseCapture(cameraId, parentCycleId, phaseId, flight, phases);
            if (!phases.add(phaseKey)) {
                return BeginResult.IN_FLIGHT;
            }
            flight.set(true);
            if (boundary != null) {
                boundary.set(0L);
            }
            AtomicBoolean cancelFlag = cancelRequested.get(cameraId);
            if (cancelFlag != null) {
                cancelFlag.set(false);
            }
            AtomicLong activeSeq = activeTriggerSequence.get(cameraId);
            if (activeSeq != null) {
                activeSeq.set(Math.max(0L, triggerSequence));
            }
            return BeginResult.STARTED;
        }
    }

    /** Сериализует preview-only capture с обычной инспекцией той же камеры. */
    public boolean tryBeginPreviewCapture(int cameraId) {
        AtomicBoolean flag = inspectionEnabled.get(cameraId);
        AtomicBoolean flight = inFlight.get(cameraId);
        if (flag == null || flight == null) {
            return false;
        }
        synchronized (flight) {
            if (flag.get()) {
                return false;
            }
            return flight.compareAndSet(false, true);
        }
    }

    public void endPreviewCapture(int cameraId) {
        endInspection(cameraId);
    }

    public boolean awaitAllIdle(long timeoutMs) {
        long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
        for (Integer cameraId : cameraIds()) {
            AtomicBoolean flight = inFlight.get(cameraId);
            if (flight == null) {
                continue;
            }
            synchronized (flight) {
                while (flight.get()) {
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0L) {
                        return false;
                    }
                    try {
                        long waitMs = Math.max(1L, remainingNanos / 1_000_000L);
                        flight.wait(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Атомарно относительно preview-start включает все камеры на границе новой группы. */
    public boolean armAllInspectionAfter(long triggerSequence) {
        List<Integer> ids = cameraIds().stream().sorted().toList();
        return armAllInspectionAfter(ids, 0, Math.max(0L, triggerSequence));
    }

    private boolean armAllInspectionAfter(List<Integer> ids, int index, long triggerSequence) {
        if (index >= ids.size()) {
            for (Integer cameraId : ids) {
                resumeAfterTriggerSequence.get(cameraId).set(triggerSequence);
                inspectionEnabled.get(cameraId).set(true);
            }
            return true;
        }
        AtomicBoolean flight = inFlight.get(ids.get(index));
        if (flight == null) {
            return false;
        }
        synchronized (flight) {
            if (flight.get()) {
                return false;
            }
            return armAllInspectionAfter(ids, index + 1, triggerSequence);
        }
    }

    /** Активный triggerSequence пиров той же группы — для Stop→Start rejoin в текущий цикл. */
    public Long findActivePeerTriggerSequence(int cameraId, Collection<Integer> peerCameraIds) {
        if (peerCameraIds == null || peerCameraIds.isEmpty()) {
            return null;
        }
        long best = 0L;
        for (Integer peerId : peerCameraIds) {
            if (peerId == null || peerId == cameraId) {
                continue;
            }
            if (!isInspectionInFlight(peerId)) {
                continue;
            }
            AtomicLong activeSeq = activeTriggerSequence.get(peerId);
            long seq = activeSeq == null ? 0L : activeSeq.get();
            if (seq > best) {
                best = seq;
            }
        }
        return best > 0L ? best : null;
    }

    public long nextInspectionId(int cameraId) {
        AtomicLong sequence = inspectionSequence.get(cameraId);
        if (sequence == null) {
            throw new IllegalArgumentException("unknown camera_id=" + cameraId);
        }
        return sequence.incrementAndGet();
    }

    public void endInspection(int cameraId) {
        AtomicBoolean flight = inFlight.get(cameraId);
        if (flight == null) {
            return;
        }
        synchronized (flight) {
            Set<PhaseKey> phases = inFlightPhases.get(cameraId);
            if (phases != null) {
                phases.clear();
            }
            finishIfIdle(cameraId, flight);
        }
    }

    public void endInspection(int cameraId, long parentCycleId, int phaseId) {
        AtomicBoolean flight = inFlight.get(cameraId);
        if (flight == null) {
            return;
        }
        synchronized (flight) {
            Set<PhaseKey> phases = inFlightPhases.get(cameraId);
            if (phases != null) {
                phases.remove(new PhaseKey(Math.max(0L, parentCycleId), Math.max(0, phaseId)));
            }
            finishIfIdle(cameraId, flight);
        }
    }

    private void awaitPriorPhaseCapture(
            int cameraId,
            long parentCycleId,
            int phaseId,
            AtomicBoolean flight,
            Set<PhaseKey> phases
    ) {
        if (!awaitPriorPhase || phaseId <= 0) {
            return;
        }
        PhaseKey prior = new PhaseKey(Math.max(0L, parentCycleId), phaseId - 1);
        if (!phases.contains(prior)) {
            return;
        }
        LOG.info(
                "sync_diag channel=inspect event=phase_capture_wait cam={} parent_cycle={} phase={} waiting_for_phase={}",
                cameraId,
                parentCycleId,
                phaseId,
                phaseId - 1
        );
        while (phases.contains(prior)) {
            try {
                flight.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.info(
                "sync_diag channel=inspect event=phase_capture_wait_done cam={} parent_cycle={} phase={}",
                cameraId,
                parentCycleId,
                phaseId
        );
    }

    private void finishIfIdle(int cameraId, AtomicBoolean flight) {
        Set<PhaseKey> phases = inFlightPhases.get(cameraId);
        if (phases != null && !phases.isEmpty()) {
            return;
        }
        flight.set(false);
        AtomicBoolean cancelFlag = cancelRequested.get(cameraId);
        if (cancelFlag != null) {
            cancelFlag.set(false);
        }
        AtomicLong activeSeq = activeTriggerSequence.get(cameraId);
        if (activeSeq != null) {
            activeSeq.set(0L);
        }
        flight.notifyAll();
    }

    public boolean requestCancel(int cameraId) {
        return requestCancel(cameraId, false);
    }

    /**
     * @param keepPhase1 when true, do not cancel if the only in-flight phases are phase≥1
     *                   (phase1 must still wait for Line0 after DI2↓).
     */
    public boolean requestCancel(int cameraId, boolean keepPhase1) {
        AtomicBoolean flight = inFlight.get(cameraId);
        AtomicBoolean cancelFlag = cancelRequested.get(cameraId);
        if (flight == null || cancelFlag == null) {
            return false;
        }
        synchronized (flight) {
            if (!flight.get()) {
                return false;
            }
            if (keepPhase1 && onlyPhase1OrLaterInFlight(cameraId)) {
                return false;
            }
            cancelFlag.set(true);
            return true;
        }
    }

    private boolean onlyPhase1OrLaterInFlight(int cameraId) {
        Set<PhaseKey> phases = inFlightPhases.get(cameraId);
        if (phases == null || phases.isEmpty()) {
            return false;
        }
        for (PhaseKey phase : phases) {
            if (phase.phaseId() <= 0) {
                return false;
            }
        }
        return true;
    }

    public boolean isCancelRequested(int cameraId) {
        AtomicBoolean cancelFlag = cancelRequested.get(cameraId);
        return cancelFlag != null && cancelFlag.get();
    }

    public boolean runIfInspectionActive(int cameraId, Runnable action) {
        AtomicBoolean flag = inspectionEnabled.get(cameraId);
        AtomicBoolean flight = inFlight.get(cameraId);
        AtomicBoolean cancelFlag = cancelRequested.get(cameraId);
        if (flag == null || flight == null || cancelFlag == null) {
            return false;
        }
        synchronized (flight) {
            if (!flag.get() || !flight.get() || cancelFlag.get()) {
                return false;
            }
            action.run();
            return true;
        }
    }
}
