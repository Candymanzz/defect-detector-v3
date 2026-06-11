package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private PerCameraInspectionGate(
            Map<Integer, AtomicBoolean> enabled,
            Map<Integer, AtomicBoolean> inFlight,
            Map<Integer, AtomicBoolean> cancelRequested
    ) {
        this.inspectionEnabled.putAll(enabled);
        this.inFlight.putAll(inFlight);
        this.cancelRequested.putAll(cancelRequested);
    }

    @SuppressWarnings("unchecked")
    public static PerCameraInspectionGate fromCameras(List<Map<String, Object>> cameras) {
        ConcurrentHashMap<Integer, AtomicBoolean> enabled = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, AtomicBoolean> flight = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, AtomicBoolean> cancelled = new ConcurrentHashMap<>();
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
            }
        }
        return new PerCameraInspectionGate(enabled, flight, cancelled);
    }

    public boolean isKnownCamera(int cameraId) {
        return inspectionEnabled.containsKey(cameraId);
    }

    public boolean isInspectionEnabled(int cameraId) {
        AtomicBoolean flag = inspectionEnabled.get(cameraId);
        return flag != null && flag.get();
    }

    public void setInspectionEnabled(int cameraId, boolean enabled) {
        AtomicBoolean flag = inspectionEnabled.get(cameraId);
        if (flag != null) {
            flag.set(enabled);
        }
    }

    public BeginResult tryBeginInspection(int cameraId) {
        if (!isInspectionEnabled(cameraId)) {
            return BeginResult.DISABLED;
        }
        AtomicBoolean flight = inFlight.get(cameraId);
        if (flight == null) {
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

    public void endInspection(int cameraId) {
        AtomicBoolean flight = inFlight.get(cameraId);
        if (flight != null) {
            flight.set(false);
        }
        AtomicBoolean cancelFlag = cancelRequested.get(cameraId);
        if (cancelFlag != null) {
            cancelFlag.set(false);
        }
    }

    public boolean requestCancel(int cameraId) {
        AtomicBoolean flight = inFlight.get(cameraId);
        AtomicBoolean cancelFlag = cancelRequested.get(cameraId);
        if (flight == null || cancelFlag == null) {
            return false;
        }
        if (!flight.get()) {
            return false;
        }
        cancelFlag.set(true);
        return true;
    }

    public boolean isCancelRequested(int cameraId) {
        AtomicBoolean cancelFlag = cancelRequested.get(cameraId);
        return cancelFlag != null && cancelFlag.get();
    }
}
