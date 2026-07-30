package com.example.iml.orchestrator.integration.trigger.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoInputDirectionAutoCaptureTest {

    @Test
    void awaitsDi2HighBeforeArm() {
        var auto = new IoInputDirectionAutoCapture();
        auto.tryArmOnDi2(false, false);
        assertFalse(auto.isDirectionArmed());
        assertEquals(IoInputDirectionAutoCapture.CycleDirection.UNKNOWN, auto.directionAtRise());
    }

    @Test
    void armsOnDi2High() {
        var auto = new IoInputDirectionAutoCapture();
        auto.tryArmOnDi2(true, false);
        assertTrue(auto.isDirectionArmed());
        assertTrue(auto.isForward());
        assertTrue(auto.allowsInstantCapture(true));
    }

    @Test
    void ignoresFurtherDi2ChangesAfterArm() {
        var auto = new IoInputDirectionAutoCapture();
        auto.tryArmOnDi2(true, false);
        auto.tryArmOnDi2(false, false);
        assertTrue(auto.isForward());
    }

    @Test
    void di3RiseDoesNotChangeDirection() {
        var auto = new IoInputDirectionAutoCapture();
        auto.tryArmOnDi2(true, false);
        auto.onDi3Rising(false);
        assertTrue(auto.isForward());
    }

    @Test
    void invertArmsOnDi2Low() {
        var auto = new IoInputDirectionAutoCapture();
        auto.tryArmOnDi2(false, true);
        assertTrue(auto.isDirectionArmed());
        assertTrue(auto.isForward());
    }

    @Test
    void isCaptureSignalMatchesForwardRaw() {
        var auto = new IoInputDirectionAutoCapture();
        assertTrue(auto.isCaptureSignal(true, false));
        assertFalse(auto.isCaptureSignal(false, false));
    }
}
