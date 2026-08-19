package com.example.iml.orchestrator.integration.pipeline.bucket;

import java.util.List;

/** Независимое ведро: группа камер с собственным решением для робота. */
public record BucketGroup(
        int phaseId,
        int id,
        List<Integer> cameraIds
) {
    public BucketGroup(int id, List<Integer> cameraIds) {
        this(0, id, cameraIds);
    }

    public BucketGroup {
        phaseId = Math.max(0, phaseId);
        cameraIds = List.copyOf(cameraIds);
    }
}
