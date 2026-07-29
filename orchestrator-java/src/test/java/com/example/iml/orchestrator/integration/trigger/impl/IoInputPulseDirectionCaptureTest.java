package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.gpio.LineDiscreteTriggerEvaluator;
import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DI3 часто приходит раньше DI2; съёмка допустима, когда DI2=1 внутри импульса DI3.
 */
class IoInputPulseDirectionCaptureTest {

    @Test
    void directionAfterTriggerRisingShouldAllowCaptureWhileTriggerHigh() {
        boolean triggerActive = false;
        boolean directionActive = false;
        boolean captureFiredThisPulse = false;

        triggerActive = true;
        assertFalse(directionActive);

        directionActive = true;
        assertTrue(triggerActive);
        assertFalse(captureFiredThisPulse);
        assertTrue(shouldCaptureDuringPulse(triggerActive, captureFiredThisPulse, true, false, directionActive));

        captureFiredThisPulse = true;
        assertFalse(shouldCaptureDuringPulse(triggerActive, captureFiredThisPulse, true, true, directionActive));
    }

    @Test
    void immediateCaptureWhenDirectionAlreadyHighAtTriggerRising() {
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(TriggerEdgeMode.RISING);
        assertEquals(
                LineDiscreteTriggerEvaluator.Decision.FIRE,
                evaluator.evaluate(true, true, true, true, false)
        );
    }

    private static boolean shouldCaptureDuringPulse(
            boolean triggerActive,
            boolean captureFiredThisPulse,
            boolean requireDirection,
            boolean requireWork,
            boolean directionActive
    ) {
        if (!triggerActive || captureFiredThisPulse) {
            return false;
        }
        if (requireWork) {
            return false;
        }
        return !requireDirection || directionActive;
    }
}
