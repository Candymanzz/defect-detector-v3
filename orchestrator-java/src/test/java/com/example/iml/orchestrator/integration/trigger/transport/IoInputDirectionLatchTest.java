package com.example.iml.orchestrator.integration.trigger.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoInputDirectionLatchTest {

    @Test
    void recordsDirectionThatArrivesAfterTriggerArm() {
        IoInputDirectionLatch latch = new IoInputDirectionLatch();
        latch.onTriggerArm(false);
        assertFalse(latch.isSatisfied(false));

        latch.onDirectionChange(true, true);
        assertTrue(latch.isSatisfied(false));
        assertTrue(latch.effectiveForFallingEdge(false));
    }

    @Test
    void resetsAfterTriggerRelease() {
        IoInputDirectionLatch latch = new IoInputDirectionLatch();
        latch.onTriggerArm(false);
        latch.onDirectionChange(true, true);
        latch.onTriggerRelease();

        assertFalse(latch.seenWhileTriggered());
        assertFalse(latch.isSatisfied(false));
    }
}
