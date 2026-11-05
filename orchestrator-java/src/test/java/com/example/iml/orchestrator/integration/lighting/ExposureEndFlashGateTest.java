package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.camera.NativeCameraEvents.Event;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static com.example.iml.orchestrator.integration.camera.NativeCameraEvents.*;
import static org.junit.jupiter.api.Assertions.*;

class ExposureEndFlashGateTest {
    private Event event(int camera, int kind, long ticks, long host, long drops) {
        return new Event(camera, kind, ticks, host, drops);
    }
    private void pair(ExposureEndFlashGate gate, int camera, long tick, long host) {
        assertFalse(gate.accept(event(camera, EXPOSURE_START, tick, host, 0)));
        gate.accept(event(camera, EXPOSURE_END, tick + 10, host + 1, 0));
    }

    @Test void validatesConsecutiveCompleteWindowsBeforeEarlyOff() {
        var gate = new ExposureEndFlashGate(Set.of(0, 1), 2);
        for (int i = 0; i < 2; ++i) {
            gate.begin(100 + i * 100);
            pair(gate, 0, 100 + i * 100, 101 + i * 100);
            assertFalse(gate.accept(event(1, EXPOSURE_START, 100 + i * 100, 101 + i * 100, 0)));
            assertFalse(gate.accept(event(1, EXPOSURE_END, 110 + i * 100, 102 + i * 100, 0)));
            assertTrue(gate.finish());
        }
        gate.begin(300);
        pair(gate, 0, 300, 301);
        assertFalse(gate.accept(event(1, EXPOSURE_START, 300, 301, 0)));
        assertTrue(gate.accept(event(1, EXPOSURE_END, 310, 302, 0)));
        assertFalse(gate.accept(event(1, EXPOSURE_END, 310, 302, 0)), "duplicate must not trigger twice");
    }

    @Test void missingCameraFallsBackAndRestartsValidation() {
        var gate = new ExposureEndFlashGate(Set.of(0, 1), 1);
        gate.begin(100); pair(gate, 0, 100, 101); pair(gate, 1, 100, 101); gate.finish();
        gate.begin(200); pair(gate, 0, 200, 201);
        assertFalse(gate.finish()); assertEquals(0, gate.consecutive());
        gate.begin(300); pair(gate, 0, 300, 301);
        gate.accept(event(1, EXPOSURE_START, 300, 301, 0));
        assertFalse(gate.accept(event(1, EXPOSURE_END, 310, 302, 0)));
    }

    @Test void staleOrUnmatchedEndCannotExtinguishNextWindow() {
        var gate = new ExposureEndFlashGate(Set.of(0), 1);
        gate.begin(100); pair(gate, 0, 100, 101); gate.finish(); gate.begin(200);
        assertFalse(gate.accept(event(0, EXPOSURE_START, 200, 199, 0)));
        assertFalse(gate.accept(event(0, EXPOSURE_END, 210, 201, 0)));
        assertFalse(gate.accept(event(4, EXPOSURE_END, 210, 201, 0)));
        assertFalse(gate.finish());
    }

    @Test void queueLossDisarmsEvenWhenAllEndsArrive() {
        var gate = new ExposureEndFlashGate(Set.of(0), 1);
        gate.begin(100); pair(gate, 0, 100, 101); gate.finish(); gate.begin(200);
        gate.accept(event(0, EXPOSURE_START, 200, 201, 0));
        assertFalse(gate.accept(event(0, EXPOSURE_END, 210, 202, 1)));
        assertFalse(gate.finish()); assertEquals(0, gate.consecutive());
    }

    @Test void outOfOrderEndAndExtraStartInvalidateCompletion() {
        var gate = new ExposureEndFlashGate(Set.of(0), 1);
        gate.begin(100); pair(gate, 0, 100, 101);
        gate.accept(event(0, EXPOSURE_START, 120, 103, 0));
        assertFalse(gate.finish());
        gate.begin(200); gate.accept(event(0, EXPOSURE_START, 220, 201, 0));
        assertFalse(gate.accept(event(0, EXPOSURE_END, 210, 202, 0)));
        assertFalse(gate.finish());
    }
}
