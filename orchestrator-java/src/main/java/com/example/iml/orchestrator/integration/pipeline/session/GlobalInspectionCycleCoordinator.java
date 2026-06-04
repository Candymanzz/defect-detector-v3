package com.example.iml.orchestrator.integration.pipeline.session;

import java.util.Set;
import java.util.concurrent.Phaser;

/**
 * Global per-cycle barrier across all active cameras.
 * <p>
 * Guarantees that the next capture cycle starts only after every camera
 * finishes the previous inspection cycle (including decision/fanout stage).
 */
public final class GlobalInspectionCycleCoordinator {

    private final Set<Integer> cameraIds;
    private final Phaser phaser;

    public GlobalInspectionCycleCoordinator(Set<Integer> cameraIds) {
        this.cameraIds = cameraIds == null ? Set.of() : Set.copyOf(cameraIds);
        this.phaser = new Phaser(this.cameraIds.size());
    }

    public boolean enabled() {
        return cameraIds.size() > 1;
    }

    public void awaitCycleStart() {
        if (!enabled()) {
            return;
        }
        phaser.arriveAndAwaitAdvance();
    }

    public void awaitCycleFinish() {
        if (!enabled()) {
            return;
        }
        phaser.arriveAndAwaitAdvance();
    }
}
