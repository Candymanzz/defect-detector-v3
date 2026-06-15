package com.example.iml.orchestrator.integration.pipeline.bucket;

import java.util.List;

/** Независимое ведро: группа камер с собственным решением для робота. */
public record BucketGroup(
        int id,
        List<Integer> cameraIds
) {
    public BucketGroup {
        cameraIds = List.copyOf(cameraIds);
    }
}
