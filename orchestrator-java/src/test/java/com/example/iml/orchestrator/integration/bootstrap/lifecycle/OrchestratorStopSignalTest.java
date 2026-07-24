package com.example.iml.orchestrator.integration.bootstrap.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestratorStopSignalTest {

    @Test
    void requestUnblocksAwait() throws Exception {
        OrchestratorStopSignal signal = new OrchestratorStopSignal();
        assertFalse(signal.isRequested());
        signal.request("frontend_exited");
        assertTrue(signal.isRequested());
        assertEquals("frontend_exited", signal.reason());
        assertTrue(signal.await(100, TimeUnit.MILLISECONDS));
    }
}
