package com.example.iml.orchestrator.integration.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Последний успешный ответ java-geometry по камере (для HTTP-клиентов оркестратора).
 */
public final class GeometrySnapshotCache {

    public record Snapshot(long frameId, long updatedAtEpochMs, Map<String, Object> geometryHeader) {
    }

    private final ConcurrentHashMap<Integer, Snapshot> byCamera = new ConcurrentHashMap<>();

    public void record(int cameraId, long frameId, Map<String, Object> geometryHeader) {
        if (geometryHeader == null) {
            return;
        }
        byCamera.put(cameraId, new Snapshot(frameId, System.currentTimeMillis(), new HashMap<>(geometryHeader)));
    }

    public Optional<Snapshot> get(int cameraId) {
        return Optional.ofNullable(byCamera.get(cameraId));
    }

    public Set<Integer> cameraIds() {
        return byCamera.keySet();
    }
}
