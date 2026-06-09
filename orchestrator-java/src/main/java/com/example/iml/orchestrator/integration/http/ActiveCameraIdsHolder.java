package com.example.iml.orchestrator.integration.http;

import java.util.Collection;
import java.util.List;

/**
 * Runtime camera availability for HTTP endpoints initialized before camera workers.
 */
public final class ActiveCameraIdsHolder {

    private volatile List<Integer> cameraIds;

    public ActiveCameraIdsHolder(Collection<Integer> initialCameraIds) {
        set(initialCameraIds);
    }

    public List<Integer> get() {
        return cameraIds;
    }

    public void set(Collection<Integer> cameraIds) {
        this.cameraIds = cameraIds == null
                ? List.of()
                : cameraIds.stream().distinct().sorted().toList();
    }
}
