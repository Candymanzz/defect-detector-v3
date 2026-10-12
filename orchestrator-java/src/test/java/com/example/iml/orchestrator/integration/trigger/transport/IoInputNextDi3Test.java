package com.example.iml.orchestrator.integration.trigger.transport;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
import com.example.iml.orchestrator.integration.trigger.config.UdpTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IoInputNextDi3Test {
    @Test void captureRequiresDirectionHighAndIgnoresReturnStroke() {
        var config = IoInputDiscreteConfig.parse(Map.of("inspection_trigger", Map.of("io_input", Map.of(
                "di3_only", true, "direction_arm_next_di3", false, "direction_latch", false,
                "require_direction", true, "require_work", false))), 0);
        try (var bus = new InspectionTriggerBus(List.of(0));
             var transport = new IoInputTriggerTransport(LogManager.getLogger(getClass()),
                     UdpTriggerConfig.defaults(), config, bus, () -> {})) {
            transport.setHardwareObservationSource();
            pulse(transport, 3);
            assertEquals(0, bus.lastDispatchedSequence());
            for (int cycle = 1; cycle <= 3; cycle++) {
                transport.applyDiChange(new IoInputDiChange(2, true));
                pulse(transport, 3);
                assertEquals(cycle, bus.lastDispatchedSequence());
                transport.applyDiChange(new IoInputDiChange(2, false));
                pulse(transport, 3);
                assertEquals(cycle, bus.lastDispatchedSequence());
            }
            transport.applyDiChange(null);
            pulse(transport, 3);
            assertEquals(3, bus.lastDispatchedSequence());
        }
    }

    private static void pulse(IoInputTriggerTransport transport, int port) {
        transport.applyDiChange(new IoInputDiChange(port, true));
        transport.applyDiChange(new IoInputDiChange(port, false));
    }
}
