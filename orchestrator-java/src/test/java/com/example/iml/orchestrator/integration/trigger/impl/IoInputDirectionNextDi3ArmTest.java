package com.example.iml.orchestrator.integration.trigger.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoInputDirectionNextDi3ArmTest {

    @Test
    void armsOnWrongDirectionAndCapturesOnNextDi3() {
        var arm = new IoInputDirectionNextDi3Arm();

        assertEquals(
                IoInputDirectionNextDi3Arm.Di3RisingAction.ARM_NEXT_DI3,
                arm.onDi3Rising(false, true)
        );
        assertTrue(arm.armed());

        assertEquals(
                IoInputDirectionNextDi3Arm.Di3RisingAction.CAPTURE_ARMED,
                arm.onDi3Rising(false, true)
        );
        assertFalse(arm.armed());
    }

    @Test
    void capturesImmediatelyWhenDirectionAlreadyOk() {
        var arm = new IoInputDirectionNextDi3Arm();

        assertEquals(
                IoInputDirectionNextDi3Arm.Di3RisingAction.CAPTURE_NOW,
                arm.onDi3Rising(true, true)
        );
        assertFalse(arm.armed());
    }
}
