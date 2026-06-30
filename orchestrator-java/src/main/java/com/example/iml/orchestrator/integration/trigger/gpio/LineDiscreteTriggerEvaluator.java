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

    private final boolean requireWork;
    private final boolean requireDirection;
    private boolean previousTriggerActive;

    public LineDiscreteTriggerEvaluator() {
        this(true, true);
    }

    public LineDiscreteTriggerEvaluator(boolean requireWork, boolean requireDirection) {
        this.requireWork = requireWork;
        this.requireDirection = requireDirection;
    }

    /** Запомнить текущий уровень DI3 без генерации фронта (при старте опроса). */
    public void armTriggerState(boolean triggerActive) {
        previousTriggerActive = triggerActive;
    }

    public Decision evaluate(boolean workActive, boolean directionActive, boolean triggerActive) {
        boolean risingEdge = triggerActive && !previousTriggerActive;
        previousTriggerActive = triggerActive;
        if (!risingEdge) {
            return Decision.NONE;
        }
        if (requireWork && !workActive) {
            return Decision.SKIP_NOT_READY;
        }
        if (requireDirection && !directionActive) {
            return Decision.SKIP_WRONG_DIRECTION;
        }
        return Decision.FIRE;
    }

    void resetTriggerEdge() {
        previousTriggerActive = false;
    }
}
