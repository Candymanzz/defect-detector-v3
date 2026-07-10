package com.example.iml.orchestrator.integration.trigger.transport;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IoInputWorkSessionDirectionTest {

    private final IoInputWorkSessionDirection session = new IoInputWorkSessionDirection();

    @Test
    void latchesDirectionAfterWorkStartAndAllowsCaptureOnDi3Only() {
        var log = LogManager.getLogger("test");

        session.onWorkStarted(false, log);
        assertFalse(session.allowsCapture(true, true, true));

        session.onDirectionChange(true, true, log);
        assertTrue(session.allowsCapture(true, true, true));

        session.onDirectionChange(false, true, log);
        assertTrue(session.allowsCapture(true, true, true));
    }

    @Test
    void clearsSessionWhenWorkStops() {
        var log = LogManager.getLogger("test");

        session.onWorkStarted(true, log);
        assertTrue(session.allowsCapture(true, true, true));

        session.onWorkStopped(log);
        assertFalse(session.allowsCapture(true, false, true));
    }

    @Test
    void backwardSessionBlocksCapture() {
        var log = LogManager.getLogger("test");

        session.onWorkStarted(false, log);
        session.onDirectionChange(false, true, log);
        assertFalse(session.allowsCapture(true, true, true));
    }
}
