package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.gpio.LineDiscreteTriggerEvaluator;
import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DI2 часто размыкается раньше DI3; при falling edge направление берётся с момента замыкания DI3.
 * DI3 также может прийти раньше DI2 — направление фиксируется в latch на время импульса.
 */
class IoInputMonitorTriggerSequenceTest {

    @Test
    void risingSequenceWithDirectionAfterTriggerUsesLatch() {
        IoInputDirectionLatch latch = new IoInputDirectionLatch();
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(TriggerEdgeMode.RISING);
        boolean triggerActive = false;

        latch.onTriggerArm(false);
        triggerActive = true;
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.SKIP_WRONG_DIRECTION,
                evaluator.evaluate(true, latch.isSatisfied(false), triggerActive)
        );

        latch.onDirectionChange(true, true);
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.NONE,
                evaluator.evaluate(true, latch.isSatisfied(false), triggerActive)
        );
        assertTrue(latch.isSatisfied(false));
    }

    @Test
    void fallingEdgeUsesLatchSeenBeforeReleaseClearsState() {
        IoInputDirectionLatch latch = new IoInputDirectionLatch();
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(TriggerEdgeMode.FALLING);
        boolean triggerActive = false;

        latch.onTriggerArm(true);
        triggerActive = true;
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, true, triggerActive));

        latch.onDirectionChange(true, true);
        assertTrue(latch.seenWhileTriggered());

        triggerActive = false;
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.FIRE,
                evaluator.evaluate(true, latch.effectiveForFallingEdge(true), triggerActive)
        );
        assertFalse(latch.effectiveForFallingEdge(false));

        latch.onTriggerRelease();
        assertFalse(latch.effectiveForFallingEdge(false));
    }

    @Test
    void fallingEdgeSkipsOppositeDirectionArmHighReleaseLow() {
        IoInputDirectionLatch latch = new IoInputDirectionLatch();
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(TriggerEdgeMode.FALLING);
        boolean triggerActive = false;

        latch.onTriggerArm(true);
        triggerActive = true;
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, true, triggerActive));

        latch.onDirectionChange(false, true);
        triggerActive = false;
        assertFalse(latch.effectiveForFallingEdge(false));
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.SKIP_WRONG_DIRECTION,
                evaluator.evaluate(true, latch.effectiveForFallingEdge(false), triggerActive)
        );
    }

    @Test
    void forwardLineSequenceFiresOnFallingRelease() {
        IoInputDirectionLatch latch = new IoInputDirectionLatch();
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(TriggerEdgeMode.FALLING);
        boolean triggerActive = false;

        latch.onTriggerArm(false);
        triggerActive = true;
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, false, triggerActive));

        latch.onDirectionChange(true, true);
        triggerActive = false;
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.FIRE,
                evaluator.evaluate(true, latch.effectiveForFallingEdge(true), triggerActive)
        );
    }

    @Test
    void fallingEdgeFiresWhenDirectionWasActiveAtTriggerArmEvenIfReleasedBeforeFall() {
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(TriggerEdgeMode.FALLING);
        boolean directionActive = false;
        boolean triggerActive = false;
        boolean directionAtTriggerArm = false;

        directionActive = true;
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.NONE,
                evaluator.evaluate(true, directionActive, triggerActive)
        );

        directionAtTriggerArm = directionActive;
        triggerActive = true;
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.NONE,
                evaluator.evaluate(true, directionActive, triggerActive)
        );

        directionActive = false;
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.NONE,
                evaluator.evaluate(true, directionActive, triggerActive)
        );

        triggerActive = false;
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.FIRE,
                evaluator.evaluate(true, directionAtTriggerArm, triggerActive)
        );
    }
}
