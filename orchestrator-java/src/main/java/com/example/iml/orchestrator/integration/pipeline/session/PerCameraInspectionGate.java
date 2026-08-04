package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * Per-camera inspection gate: at most one in-flight cycle per camera, optional disable without stopping capture.
 */
public final class PerCameraInspectionGate {

    public enum BeginResult {
        STARTED,
        DISABLED,
        IN_FLIGHT
    }

    private final ConcurrentHashMap<Integer, AtomicBoolean> inspectionEnabled = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicBoolean> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicBoolean> cancelRequested = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicLong> inspectionSequence = new ConcurrentHashMap<>();
    /** Уведомление bucket-агрегатора: камера вышла из expected set открытых вёдер. */
    private volatile IntConsumer onCameraDisabled;
    /** Камеры, которые были включены до {@link #disableAllAndRequestCancel()} — для start-all. */
    private volatile Set<Integer> resumeCameraIds;

    private PerCameraInspectionGate(
            Map<Integer, AtomicBoolean> enabled,
            Map<Integer, AtomicBoolean> inFlight,
            Map<Integer, AtomicBoolean> cancelRequested,
            Map<Integer, AtomicLong> inspectionSequence
    ) {
        this.inspectionEnabled.putAll(enabled);
        this.inFlight.putAll(inFlight);
        this.cancelRequested.putAll(cancelRequested);
        this.inspectionSequence.putAll(inspectionSequence);
    }

    public static PerCameraInspectionGate fromCameras(List<Map<String, Object>> cameras) {
        ConcurrentHashMap<Integer, AtomicBoolean> enabled = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, AtomicBoolean> flight = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, AtomicLong> sequences = new ConcurrentHashMap<>();
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
            }
        }
        return new PerCameraInspectionGate(enabled, flight, cancelled, sequences);
    }

    public boolean isKnownCamera(int cameraId) {
        return inspectionEnabled.containsKey(cameraId);
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

    public void setOnCameraDisabled(IntConsumer onCameraDisabled) {
        this.onCameraDisabled = onCameraDisabled;
    }

    /** Останавливает инспекцию на всех известных камерах; эталон не трогает. */
    public Set<Integer> disableAllAndRequestCancel() {
        Set<Integer> previouslyEnabled = new LinkedHashSet<>();
        for (Integer cameraId : cameraIds()) {
            if (isInspectionEnabled(cameraId)) {
                previouslyEnabled.add(cameraId);
            }
        }
        resumeCameraIds = Set.copyOf(previouslyEnabled);

        Set<Integer> cancelled = new LinkedHashSet<>();
        for (Integer cameraId : previouslyEnabled) {
            if (disableInspectionAndRequestCancel(cameraId)) {
                cancelled.add(cameraId);
            }
        }
        return cancelled;
    }

    /**
     * Включает инспекцию после stop-all: восстанавливает набор камер, активных до паузы.
     * Если снимка нет — включает все известные камеры.
     */
    public Set<Integer> enableAll() {
        Set<Integer> toEnable = resumeCameraIds != null ? resumeCameraIds : Set.copyOf(cameraIds());
        resumeCameraIds = null;
        Set<Integer> changed = new LinkedHashSet<>();
        for (Integer cameraId : toEnable) {
            if (!isKnownCamera(cameraId)) {
                continue;
            }
            if (!isInspectionEnabled(cameraId)) {
                setInspectionEnabled(cameraId, true);
                changed.add(cameraId);
            }
        }
        return changed;
    }

    public void setInspectionEnabled(int cameraId, boolean enabled) {
        AtomicBoolean flag = inspectionEnabled.get(cameraId);
        AtomicBoolean flight = inFlight.get(cameraId);
        boolean becameDisabled = false;
        if (flag != null && flight != null) {
            synchronized (flight) {
                becameDisabled = flag.get() && !enabled;
                flag.set(enabled);
            }
        } else if (flag != null) {
            becameDisabled = flag.get() && !enabled;
            flag.set(enabled);
        }
        if (enabled) {
            alignToGlobalMax(cameraId);
        }
        if (becameDisabled) {
            notifyCameraDisabled(cameraId);
        }
    }

    /** Подтянуть счётчик камеры к max по всем камерам (resume не с хвоста stop). */
    private void alignToGlobalMax(int cameraId) {
        long max = 0L;
        for (AtomicLong sequence : inspectionSequence.values()) {
            max = Math.max(max, sequence.get());
        }
        catchUpSequence(cameraId, max);
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
        boolean cancelled;
        boolean becameDisabled;
        synchronized (flight) {
            becameDisabled = flag.get();
            flag.set(false);
            if (!flight.get()) {
                cancelled = false;
            } else {
                cancelFlag.set(true);
                cancelled = true;
            }
        }
        if (becameDisabled) {
            notifyCameraDisabled(cameraId);
        }
        return cancelled;
    }

    private void notifyCameraDisabled(int cameraId) {
        IntConsumer listener = onCameraDisabled;
        if (listener != null) {
            listener.accept(cameraId);
        }
    }

    public BeginResult tryBeginInspection(int cameraId) {
        AtomicBoolean flight = inFlight.get(cameraId);
        if (flight == null) {
            return BeginResult.DISABLED;
        }
        synchronized (flight) {
            if (!isInspectionEnabled(cameraId)) {
                return BeginResult.DISABLED;
            }
            if (!flight.compareAndSet(false, true)) {
                return BeginResult.IN_FLIGHT;
            }
            AtomicBoolean cancelFlag = cancelRequested.get(cameraId);
            if (cancelFlag != null) {
                cancelFlag.set(false);
            }
            return BeginResult.STARTED;
        }
    }

    public long nextInspectionId(int cameraId) {
        AtomicLong sequence = inspectionSequence.get(cameraId);
        if (sequence == null) {
            throw new IllegalArgumentException("unknown camera_id=" + cameraId);
        }
        return sequence.incrementAndGet();
    }

    /**
     * Выдать inspection_id, совпадающий с текущим line trigger sequence.
     * Resume после stop вклинивается в уже прошедший счёт кадров линии, а не продолжает
     * локальный хвост камеры с момента остановки.
     */
    public long allocateInspectionId(int cameraId, long triggerSequence) {
        AtomicLong sequence = inspectionSequence.get(cameraId);
        if (sequence == null) {
            throw new IllegalArgumentException("unknown camera_id=" + cameraId);
        }
        if (triggerSequence > 0L) {
            sequence.updateAndGet(current -> Math.max(current, triggerSequence));
            return triggerSequence;
        }
        return sequence.incrementAndGet();
    }

    /**
     * Пока камера на Stop, триггеры линии всё равно идут — подтягиваем счётчик,
     * чтобы при Start сразу быть на актуальном seq.
     */
    public void catchUpSequence(int cameraId, long triggerSequence) {
        if (triggerSequence <= 0L) {
            return;
        }
        AtomicLong sequence = inspectionSequence.get(cameraId);
        if (sequence == null) {
            return;
        }
        sequence.updateAndGet(current -> Math.max(current, triggerSequence));
    }

    /** Текущий счётчик камеры (после catch-up / allocate). */
    public long currentSequence(int cameraId) {
        AtomicLong sequence = inspectionSequence.get(cameraId);
        return sequence == null ? 0L : sequence.get();
    }

    public void endInspection(int cameraId) {
        AtomicBoolean flight = inFlight.get(cameraId);
        AtomicBoolean cancelFlag = cancelRequested.get(cameraId);
        if (flight == null) {
            return;
        }
        synchronized (flight) {
            flight.set(false);
            if (cancelFlag != null) {
                cancelFlag.set(false);
            }
        }
    }

    public boolean requestCancel(int cameraId) {
        AtomicBoolean flight = inFlight.get(cameraId);
        AtomicBoolean cancelFlag = cancelRequested.get(cameraId);
        if (flight == null || cancelFlag == null) {
            return false;
        }
        synchronized (flight) {
            if (!flight.get()) {
                return false;
            }
            cancelFlag.set(true);
            return true;
        }
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
