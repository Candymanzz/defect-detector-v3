package com.example.iml.orchestrator.integration.trigger.gpio;

/**
 * Логика трёх дискретных входов линии:
 * работа=1, направление=1, фронт триггера 0→1 → съёмка.
 */
public final class LineDiscreteTriggerEvaluator {

    public enum Decision {
        NONE,
        FIRE,
        SKIP_NOT_READY,
        SKIP_WRONG_DIRECTION
    }

    private boolean previousTriggerActive;

    public Decision evaluate(boolean workActive, boolean directionActive, boolean triggerActive) {
        boolean risingEdge = triggerActive && !previousTriggerActive;
        previousTriggerActive = triggerActive;
        if (!risingEdge) {
            return Decision.NONE;
        }
        if (!workActive) {
            return Decision.SKIP_NOT_READY;
        }
        if (!directionActive) {
            return Decision.SKIP_WRONG_DIRECTION;
        }
        return Decision.FIRE;
    }

    void resetTriggerEdge() {
        previousTriggerActive = false;
    }
}
