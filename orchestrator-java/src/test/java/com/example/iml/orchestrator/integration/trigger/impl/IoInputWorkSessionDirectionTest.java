package com.example.iml.orchestrator.integration.trigger.impl;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoInputWorkSessionDirectionTest {

    private final IoInputWorkSessionDirection session = new IoInputWorkSessionDirection();

    @Test
    void latchesDirectionAfterWorkStartAndAllowsCaptureOnDi3Only() {
        var log = LogManager.getLogger("test");

        session.onWorkStarted(false, false, false, log);
        assertFalse(session.allowsCapture(true, true, true));

        session.onDirectionChange(true, true, true, false, log);
        assertTrue(session.allowsCapture(true, true, true));

        session.onDirectionChange(false, false, true, false, log);
        assertTrue(session.allowsCapture(true, true, true));
    }

    @Test
    void clearsSessionWhenWorkStops() {
        var log = LogManager.getLogger("test");

        session.onWorkStarted(true, true, false, log);
        session.onDirectionChange(true, true, true, false, log);
        assertTrue(session.allowsCapture(true, true, true));

        session.onWorkStopped(log);
        assertFalse(session.allowsCapture(true, false, true));
    }

    @Test
    void backwardSessionBlocksCapture() {
        var log = LogManager.getLogger("test");

        session.onWorkStarted(false, false, false, log);
        session.onDirectionChange(false, false, true, false, log);
        assertFalse(session.allowsCapture(true, true, true));
    }
}
