package com.example.iml.orchestrator.integration.pipeline.reference;

import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;

/** Результат {@link ReferenceSnapshotBootstrap#ensure}: актуальный эталон и время последнего захвата (0 при reuse). */
public record ReferenceBootstrapOutcome(ReferenceSnapshot snapshot, long referenceWallMs) {
}
