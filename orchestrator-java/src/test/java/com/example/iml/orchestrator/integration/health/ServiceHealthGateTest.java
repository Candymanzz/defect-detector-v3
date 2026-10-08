package com.example.iml.orchestrator.integration.health;

import org.junit.jupiter.api.Test;

import java.util.Set;
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
        gate.markUnhealthy("geometry_0");
        assertFalse(gate.healthy());
        assertEquals(1, changes.get());

        gate.markUnhealthy("geometry_0");
        assertEquals(1, changes.get());

        gate.markHealthy("geometry_0");
        assertTrue(gate.healthy());
        assertEquals(2, changes.get());
    }

    @Test
    void criticalServicesBlockVision() {
        ServiceHealthGate gate = new ServiceHealthGate();
        assertTrue(gate.healthyForVision());
        assertTrue(gate.visionBlockingReasons().isEmpty());

        gate.markUnhealthy("analis_surface");
        assertFalse(gate.healthyForVision());
        assertEquals(Set.of("analis_surface"), gate.visionBlockingReasons());
    }

    @Test
    void addOnChangedNotifiesAllListeners() {
        ServiceHealthGate gate = new ServiceHealthGate();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        gate.addOnChanged(first::incrementAndGet);
        gate.addOnChanged(second::incrementAndGet);

        gate.markUnhealthy("geometry_0");
        assertEquals(1, first.get());
        assertEquals(1, second.get());
    }

    @Test
    void affectsVisionPlcIgnoresEmptyNames() {
        assertFalse(ServiceHealthGate.affectsVisionPlc(" "));
        assertTrue(ServiceHealthGate.affectsVisionPlc("analis_surface"));
    }
}
