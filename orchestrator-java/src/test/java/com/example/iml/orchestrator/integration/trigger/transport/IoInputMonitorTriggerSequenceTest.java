package com.example.iml.orchestrator.integration.trigger.transport;

import com.example.iml.orchestrator.integration.trigger.gpio.LineDiscreteTriggerEvaluator;
import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DI2 часто размыкается раньше DI3; при falling edge направление берётся с момента замыкания DI3.
 */
class IoInputMonitorTriggerSequenceTest {

    @Test
    void fallingEdgeFiresWhenDirectionWasActiveAtTriggerArmEvenIfReleasedBeforeFall() {
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(TriggerEdgeMode.FALLING);
        boolean directionActive = false;
        boolean triggerActive = false;
        boolean directionAtTriggerArm = false;

        // DI2 rising
        directionActive = true;
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.NONE,
                evaluator.evaluate(true, directionActive, triggerActive)
        );

        // DI3 rising — latch direction
        directionAtTriggerArm = directionActive;
        triggerActive = true;
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.NONE,
                evaluator.evaluate(true, directionActive, triggerActive)
        );

        // DI2 falling before DI3 falling
        directionActive = false;
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.NONE,
                evaluator.evaluate(true, directionActive, triggerActive)
        );

        // DI3 falling — use latched direction
        triggerActive = false;
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.FIRE,
                evaluator.evaluate(true, directionAtTriggerArm, triggerActive)
        );
    }
}
