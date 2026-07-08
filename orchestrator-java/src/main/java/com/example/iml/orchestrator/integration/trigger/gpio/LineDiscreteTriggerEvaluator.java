package com.example.iml.orchestrator.integration.trigger.gpio;

/**
 * Логика трёх дискретных входов линии:
 * работа=1, направление=1, фронт триггера (rising или falling) → съёмка.
 */
public final class LineDiscreteTriggerEvaluator {

    public enum Decision {
        NONE,
        FIRE,
        SKIP_NOT_READY,
        SKIP_WRONG_DIRECTION
    }

    private final TriggerEdgeMode triggerEdge;
    private boolean previousTriggerActive;

    public LineDiscreteTriggerEvaluator() {
        this(TriggerEdgeMode.RISING);
    }

    public LineDiscreteTriggerEvaluator(TriggerEdgeMode triggerEdge) {
        this.triggerEdge = triggerEdge == null ? TriggerEdgeMode.RISING : triggerEdge;
    }

    public Decision evaluate(boolean workActive, boolean directionActive, boolean triggerActive) {
        return evaluate(workActive, directionActive, triggerActive, true);
    }

    public Decision evaluate(boolean workActive, boolean directionActive, boolean triggerActive, boolean requireDirection) {
        boolean edgeDetected = switch (triggerEdge) {
            case RISING -> triggerActive && !previousTriggerActive;
            case FALLING -> !triggerActive && previousTriggerActive;
        };
        previousTriggerActive = triggerActive;
        if (!edgeDetected) {
            return Decision.NONE;
        }
        if (!workActive) {
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
