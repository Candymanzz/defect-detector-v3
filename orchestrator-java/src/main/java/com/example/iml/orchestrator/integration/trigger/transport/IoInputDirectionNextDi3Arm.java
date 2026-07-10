package com.example.iml.orchestrator.integration.trigger.transport;

/**
 * DI3 приходит раньше DI2: при DI2=0 на фронте DI3 взводим съёмку на следующий DI3
 * (текущий импульс пропускаем — направление «догоняет» сигнал).
 */
final class IoInputDirectionNextDi3Arm {

    enum Di3RisingAction {
        CAPTURE_ARMED,
        CAPTURE_NOW,
        ARM_NEXT_DI3
    }

    private boolean armed;

    Di3RisingAction onDi3Rising(boolean directionOk, boolean requireDirection) {
        if (armed) {
            armed = false;
            return Di3RisingAction.CAPTURE_ARMED;
        }
        if (!requireDirection || directionOk) {
            return Di3RisingAction.CAPTURE_NOW;
        }
        armed = true;
        return Di3RisingAction.ARM_NEXT_DI3;
    }

    void cancelArm() {
        armed = false;
    }

    boolean armed() {
        return armed;
    }
}
