package com.example.iml.orchestrator.integration.trigger.gpio;

/**
 * Логика трёх дискретных входов линии:
 * работа=1, направление=1, фронт триггера → съёмка.
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
    private final TriggerEdge triggerEdge;
    private boolean previousTriggerActive;

    public LineDiscreteTriggerEvaluator() {
        this(true, true);
    }

    public LineDiscreteTriggerEvaluator(boolean requireWork, boolean requireDirection) {
        this(requireWork, requireDirection, TriggerEdge.RISING);
    }

    public LineDiscreteTriggerEvaluator(boolean requireWork, boolean requireDirection, TriggerEdge triggerEdge) {
        this.requireWork = requireWork;
        this.requireDirection = requireDirection;
        this.triggerEdge = triggerEdge == null ? TriggerEdge.RISING : triggerEdge;
    }

    public TriggerEdge triggerEdge() {
        return triggerEdge;
    }

    /** Запомнить текущий уровень DI3 без генерации фронта (при старте опроса). */
    public void armTriggerState(boolean triggerActive) {
        previousTriggerActive = triggerActive;
    }

    public Decision evaluate(boolean workActive, boolean directionActive, boolean triggerActive) {
        boolean risingEdge = triggerActive && !previousTriggerActive;
        boolean fallingEdge = !triggerActive && previousTriggerActive;
        previousTriggerActive = triggerActive;
        boolean edge = switch (triggerEdge) {
            case RISING -> risingEdge;
            case FALLING -> fallingEdge;
            case BOTH -> risingEdge || fallingEdge;
        };
        if (!edge) {
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
