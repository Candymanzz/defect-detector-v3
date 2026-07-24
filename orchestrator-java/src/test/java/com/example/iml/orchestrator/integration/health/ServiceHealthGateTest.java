package com.example.iml.orchestrator.integration.health;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceHealthGateTest {

    @Test
    void markUnhealthyAndHealthyFireOnChange() {
        ServiceHealthGate gate = new ServiceHealthGate();
        AtomicInteger changes = new AtomicInteger();
        gate.setOnChanged(changes::incrementAndGet);

        assertTrue(gate.healthy());
        gate.markUnhealthy("io_input_monitor");
        assertFalse(gate.healthy());
        assertEquals(1, changes.get());

        gate.markUnhealthy("io_input_monitor");
        assertEquals(1, changes.get());

        gate.markHealthy("io_input_monitor");
        assertTrue(gate.healthy());
        assertEquals(2, changes.get());
    }
}
