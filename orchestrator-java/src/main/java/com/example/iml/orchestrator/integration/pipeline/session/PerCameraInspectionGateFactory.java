package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Builds {@link PerCameraInspectionGate} state from camera YAML rows. */
final class PerCameraInspectionGateFactory {

    private PerCameraInspectionGateFactory() {
    }

    static PerCameraInspectionGate fromCameras(List<Map<String, Object>> cameras) {
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
}
