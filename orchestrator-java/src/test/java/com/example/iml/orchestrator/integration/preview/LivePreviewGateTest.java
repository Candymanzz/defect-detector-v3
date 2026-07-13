package com.example.iml.orchestrator.integration.preview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivePreviewGateTest {

    @Test
    void pauseAndImagesFlagsAreIndependent() {
        LivePreviewGate gate = new LivePreviewGate();

        gate.setPaused(true);
        gate.setImagesEnabled(false);

        assertTrue(gate.isPaused());
        assertFalse(gate.areImagesEnabled());

        gate.setPaused(false);
        gate.setImagesEnabled(true);

        assertFalse(gate.isPaused());
        assertTrue(gate.areImagesEnabled());
    }
}
