package com.example.iml.orchestrator.integration.trigger.transport;

/**
 * Рабочий ход (по линии): DI3↑ при DI2=1, внутри импульса DI2 1→0, DI3↓ при DI2=0.
 * Обратный ход: DI3↑ при DI2=0 (DI2 потом 0→1) — без prefire/dispatch.
 */
final class IoInputDirectionAutoCapture {

    enum Di3FallingAction {
        CAPTURE_FORWARD,
        REVERSE_SKIP,
        ABORT_PREFIRE,
        NONE
    }

    private boolean pulseOpen;
    private boolean capturedThisPulse;
    private boolean directionHighAtPulseStart;
    private boolean sawDi2FallDuringPulse;

    void onDi3Rising(boolean directionRawAtRise) {
        pulseOpen = true;
        capturedThisPulse = false;
        directionHighAtPulseStart = directionRawAtRise;
        sawDi2FallDuringPulse = false;
    }

    void onDi3Released() {
        pulseOpen = false;
    }

    boolean prefireAllowedAtRise(boolean directionRawAtRise) {
        return directionRawAtRise;
    }

    void onDirectionRawChange(boolean previousRaw, boolean activeRaw) {
        if (!pulseOpen || previousRaw == activeRaw) {
            return;
        }
        if (previousRaw && !activeRaw) {
            sawDi2FallDuringPulse = true;
        }
    }

    Di3FallingAction onDi3Falling(boolean directionRawAtFall) {
        if (!pulseOpen) {
            return Di3FallingAction.NONE;
        }
        pulseOpen = false;
        if (capturedThisPulse) {
            return Di3FallingAction.NONE;
        }
        if (!directionHighAtPulseStart) {
            return Di3FallingAction.REVERSE_SKIP;
        }
        if (!directionRawAtFall && sawDi2FallDuringPulse) {
            capturedThisPulse = true;
            return Di3FallingAction.CAPTURE_FORWARD;
        }
        return Di3FallingAction.ABORT_PREFIRE;
    }
}
