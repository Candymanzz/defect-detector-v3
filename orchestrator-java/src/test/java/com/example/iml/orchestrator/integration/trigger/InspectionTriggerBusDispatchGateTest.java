package com.example.iml.orchestrator.integration.trigger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InspectionTriggerBusDispatchGateTest {

    private InspectionTriggerBus bus;

    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.close();
        }
    }

    @Test
    void skipsDispatchWhenServicesUnhealthy() {
        bus = new InspectionTriggerBus(List.of(0, 1));
        AtomicInteger allowed = new AtomicInteger(0);
        bus.setDispatchAllowed(() -> allowed.get() > 0);

        assertEquals(0, bus.dispatchLineBroadcastWithoutPrefire("io_input", null));
        assertEquals(0, bus.clearAllPending());

        allowed.set(1);
        assertEquals(2, bus.dispatchLineBroadcastWithoutPrefire("io_input", null));
    }

    @Test
    void clearAllPendingRemovesQueuedEvents() {
        bus = new InspectionTriggerBus(List.of(0));
        bus.setDispatchAllowed(() -> true);
        bus.dispatchLineBroadcastWithoutPrefire("io_input", null);
        assertEquals(1, bus.clearAllPending());
        assertEquals(0, bus.clearAllPending());
    }
}
