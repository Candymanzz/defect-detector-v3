package com.example.iml.orchestrator.integration.io.mvs;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IoFaultPublisherTest {
    @Test void onePulsePerEpisodeAndNoPulseOnRecovery() {
        List<String> pulses = new ArrayList<>();
        try (var publisher = new IoFaultPublisher("Timer2", "Out2", 60000)) {
            publisher.signal(true); // Fault before IO is ready must be retained.
            publisher.bind((timer, line) -> pulses.add(timer + "/" + line));
            publisher.signal(true); publisher.deliver();
            assertEquals(List.of("Timer2/Out2"), pulses);
            publisher.signal(false); publisher.deliver();
            assertEquals(1, pulses.size());
            publisher.signal(true);
            assertEquals(2, pulses.size());
        }
    }
    @Test void failedDeliveryRetriesButRecoveryCancelsPendingFault() {
        int[] attempts = {0};
        try (var publisher = new IoFaultPublisher("Timer2", "Out2", 60000)) {
            publisher.bind((timer, line) -> { if (++attempts[0] == 1) throw new IllegalStateException("disconnected"); });
            publisher.signal(true); publisher.deliver(); publisher.deliver();
            assertEquals(2, attempts[0]);
            publisher.signal(false);
            publisher.bind((timer, line) -> { attempts[0]++; throw new IllegalStateException("disconnected"); });
            publisher.signal(true);
            publisher.signal(false); publisher.deliver();
            assertEquals(3, attempts[0]);
            publisher.close(); publisher.signal(true);
            assertEquals(3, attempts[0]);
        }
    }
    @Test void parserRejectsCollisionWithRejectAndCameraRoutes() {
        Map<String, Object> integration = new HashMap<>();
        integration.put("inspection_trigger", Map.of("mvs_io", Map.of("enabled", true, "outputs", Map.of())));
        integration.put("io_reject", Map.of("enabled", true, "mode", "timer", "groups",
                Map.of(0, Map.of("port", 3, "timer", "Timer3", "line", "Out3"))));
        integration.put("io_fault", Map.of("enabled", true, "timer", "Timer3", "line", "Out2"));
        assertThrows(IllegalArgumentException.class, () -> IoFaultPublisher.fromConfig(integration));
        integration.put("io_fault", Map.of("enabled", true, "timer", "Timer5", "line", "Out2"));
        assertThrows(IllegalArgumentException.class, () -> IoFaultPublisher.fromConfig(integration));
    }
}
