package com.example.iml.orchestrator.integration.trigger.transport;

/**
 * Направление DI2 в окне импульса DI3: фиксирует смену направления, даже если DI3 пришёл раньше DI2.
 */
final class IoInputDirectionLatch {

    private volatile boolean atTriggerArm;
    private volatile boolean seenWhileTriggered;

    void onTriggerArm(boolean directionActive) {
        atTriggerArm = directionActive;
        seenWhileTriggered = directionActive;
    }

    void onTriggerRelease() {
        atTriggerArm = false;
        seenWhileTriggered = false;
    }

    void onDirectionChange(boolean directionActive, boolean triggerActive) {
        if (directionActive && triggerActive) {
            seenWhileTriggered = true;
        }
    }

    boolean isSatisfied(boolean directionActive) {
        return seenWhileTriggered || directionActive;
    }

    boolean effectiveForFallingEdge(boolean directionActive) {
        return seenWhileTriggered || atTriggerArm || directionActive;
    }

    boolean seenWhileTriggered() {
        return seenWhileTriggered;
    }

    boolean atTriggerArm() {
        return atTriggerArm;
    }
}
