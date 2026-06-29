package com.example.iml.orchestrator.integration.trigger.gpio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LineDiscreteTriggerEvaluatorTest {

    private final LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator();

    @Test
    void firesOnTriggerRisingEdgeWhenWorkAndDirectionActive() {
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, true, false));
        assertEquals(LineDiscreteTriggerEvaluator.Decision.FIRE, evaluator.evaluate(true, true, true));
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, true, true));
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, true, false));
    }

    @Test
    void skipsWhenConveyorNotRunning() {
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(false, true, false));
        assertEquals(LineDiscreteTriggerEvaluator.Decision.SKIP_NOT_READY, evaluator.evaluate(false, true, true));
    }

    @Test
    void skipsWhenDirectionInactive() {
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, false, false));
        assertEquals(LineDiscreteTriggerEvaluator.Decision.SKIP_WRONG_DIRECTION, evaluator.evaluate(true, false, true));
    }
}
