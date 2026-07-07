package com.example.iml.orchestrator.integration.trigger.gpio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LineDiscreteTriggerEvaluatorTest {

    @Test
    void firesOnTriggerRisingEdgeWhenWorkAndDirectionActive() {
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(TriggerEdgeMode.RISING);
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, true, false));
        assertEquals(LineDiscreteTriggerEvaluator.Decision.FIRE, evaluator.evaluate(true, true, true));
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, true, true));
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, true, false));
    }

    @Test
    void firesOnTriggerFallingEdgeWhenWorkAndDirectionActive() {
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(TriggerEdgeMode.FALLING);
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, true, false));
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, true, true));
        assertEquals(LineDiscreteTriggerEvaluator.Decision.FIRE, evaluator.evaluate(true, true, false));
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, true, false));
    }

    @Test
    void skipsWhenConveyorNotRunningOnRisingEdge() {
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(TriggerEdgeMode.RISING);
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(false, true, false));
        assertEquals(LineDiscreteTriggerEvaluator.Decision.SKIP_NOT_READY, evaluator.evaluate(false, true, true));
    }

    @Test
    void skipsWhenConveyorNotRunningOnFallingEdge() {
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(TriggerEdgeMode.FALLING);
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(false, true, false));
        evaluator.evaluate(false, true, true);
        assertEquals(LineDiscreteTriggerEvaluator.Decision.SKIP_NOT_READY, evaluator.evaluate(false, true, false));
    }

    @Test
    void skipsWhenDirectionInactiveOnRisingEdge() {
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(TriggerEdgeMode.RISING);
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, false, false));
        assertEquals(LineDiscreteTriggerEvaluator.Decision.SKIP_WRONG_DIRECTION, evaluator.evaluate(true, false, true));
    }

    @Test
    void skipsWhenDirectionInactiveOnFallingEdge() {
        LineDiscreteTriggerEvaluator evaluator = new LineDiscreteTriggerEvaluator(TriggerEdgeMode.FALLING);
        assertEquals(LineDiscreteTriggerEvaluator.Decision.NONE, evaluator.evaluate(true, false, false));
        evaluator.evaluate(true, false, true);
        assertEquals(LineDiscreteTriggerEvaluator.Decision.SKIP_WRONG_DIRECTION, evaluator.evaluate(true, false, false));
    }
}
