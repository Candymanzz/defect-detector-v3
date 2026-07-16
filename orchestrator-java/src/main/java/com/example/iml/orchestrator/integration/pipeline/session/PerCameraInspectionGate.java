package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    @SuppressWarnings("unchecked")
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

    public void setInspectionEnabled(int cameraId, boolean enabled) {
        AtomicBoolean flag = inspectionEnabled.get(cameraId);
        AtomicBoolean flight = inFlight.get(cameraId);
        if (flag != null && flight != null) {
            synchronized (flight) {
                flag.set(enabled);
            }
        } else if (flag != null) {
            flag.set(enabled);
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
