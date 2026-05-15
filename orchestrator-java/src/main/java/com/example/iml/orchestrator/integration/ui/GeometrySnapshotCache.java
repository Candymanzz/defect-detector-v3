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

    /** Снимок только если кэшированный {@code frame_id} совпадает с запрошенным кадром инспекции. */
    public Optional<Snapshot> getIfFrameMatches(int cameraId, long frameId) {
        if (frameId < 0) {
            return Optional.empty();
        }
        return get(cameraId).filter(s -> s.frameId() == frameId);
    }

    public Set<Integer> cameraIds() {
        return byCamera.keySet();
    }
}
