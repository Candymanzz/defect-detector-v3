package com.example.iml.orchestrator.integration.clientapi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streak HTTP 5xx на /inspect-shm должен помечать member unhealthy и включать UI backoff.
 */
class AnalisSurfaceInspectTransportFaultTest {

    @AfterEach
    void resetBackoff() {
        AnalisSurfaceHttpBinaryRpcSupervisor.clearPoolTransportFaultBackoff();
    }

    @Test
    void inspectTransportFailureStreakMarksUnhealthyAndSkipsUiHeatmap() throws Exception {
        AnalisSurfaceHttpBinaryRpcSupervisor.clearPoolTransportFaultBackoff();
        AnalisSurfaceHttpBinaryRpcSupervisor supervisor =
                new AnalisSurfaceHttpBinaryRpcSupervisor("analis-test", "http://127.0.0.1:65530", 500);
        AtomicBoolean unhealthy = new AtomicBoolean(false);
        supervisor.setHealthListener(ok -> {
            if (!ok) {
                unhealthy.set(true);
            }
        });

        Method fail = AnalisSurfaceHttpBinaryRpcSupervisor.class
                .getDeclaredMethod("noteInspectTransportFailure", int.class);
        fail.setAccessible(true);
        for (int i = 0; i < 5; i++) {
            fail.invoke(supervisor, 500);
        }

        assertTrue(supervisor.hasInspectTransportFault());
        assertTrue(unhealthy.get());
        assertTrue(AnalisSurfaceHttpBinaryRpcSupervisor.shouldSkipUiHeatmap());

        supervisor.clearInspectTransportFault();
        AnalisSurfaceHttpBinaryRpcSupervisor.clearPoolTransportFaultBackoff();
        assertFalse(supervisor.hasInspectTransportFault());
        assertFalse(AnalisSurfaceHttpBinaryRpcSupervisor.shouldSkipUiHeatmap());
    }
}
