package com.example.iml.orchestrator.integration.trigger.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoInputDirectionAutoCaptureTest {

    @Test
    void allowsPrefireWhenDi2HighAtDi3Rise() {
        var auto = new IoInputDirectionAutoCapture();
        auto.onDi3Rising(true);
        assertTrue(auto.prefireAllowedAtRise(true));
        assertFalse(auto.prefireAllowedAtRise(false));
    }

    @Test
    void dispatchesOnDi3FallAfterDi2FallDuringPulse() {
        var auto = new IoInputDirectionAutoCapture();

        auto.onDi3Rising(true);
        auto.onDirectionRawChange(true, false);
        assertEquals(
                IoInputDirectionAutoCapture.Di3FallingAction.CAPTURE_FORWARD,
                auto.onDi3Falling(false)
        );
    }

    @Test
    void skipsReversePulseStartingWithDi2Low() {
        var auto = new IoInputDirectionAutoCapture();

        auto.onDi3Rising(false);
        assertFalse(auto.prefireAllowedAtRise(false));
        auto.onDirectionRawChange(false, true);
        assertEquals(
                IoInputDirectionAutoCapture.Di3FallingAction.REVERSE_SKIP,
                auto.onDi3Falling(true)
        );
    }

    @Test
    void abortsWhenDi2NeverFellBeforeDi3Fall() {
        var auto = new IoInputDirectionAutoCapture();

        auto.onDi3Rising(true);
        assertEquals(
                IoInputDirectionAutoCapture.Di3FallingAction.ABORT_PREFIRE,
                auto.onDi3Falling(true)
        );
    }
}
