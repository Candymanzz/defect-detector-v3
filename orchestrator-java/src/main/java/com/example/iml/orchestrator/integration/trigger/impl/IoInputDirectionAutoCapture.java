package com.example.iml.orchestrator.integration.trigger.impl;

/**
 * Фаза 1 (опционально): DI2=1 вооружает forward.
 * Фаза 2: DI3↑ открывает импульс; съёмка через {@code capture_delay_ms} после DI3↑.
 */
final class IoInputDirectionAutoCapture {

    enum CycleDirection {
        UNKNOWN,
        FORWARD
    }

    private boolean directionArmed;
    private boolean rawAtDi3Rise;

    void tryArmOnDi2(boolean raw, boolean directionInvert) {
        if (directionArmed) {
            return;
        }
        if (!isForwardRaw(raw, directionInvert)) {
            return;
        }
        directionArmed = true;
    }

    void onDi3Rising(boolean rawAtRise) {
        rawAtDi3Rise = rawAtRise;
    }

    boolean isDirectionArmed() {
        return directionArmed;
    }

    CycleDirection directionAtRise() {
        return directionArmed ? CycleDirection.FORWARD : CycleDirection.UNKNOWN;
    }

    boolean isForward() {
        return directionArmed;
    }

    boolean rawAtDi3Rise() {
        return rawAtDi3Rise;
    }

    boolean isCaptureSignal(boolean raw, boolean directionInvert) {
        return isForwardRaw(raw, directionInvert);
    }

    boolean allowsInstantCapture(boolean requireDirection) {
        return !requireDirection || directionArmed;
    }

    static boolean isForwardRaw(boolean raw, boolean directionInvert) {
        if (directionInvert) {
            return !raw;
        }
        return raw;
    }
}
