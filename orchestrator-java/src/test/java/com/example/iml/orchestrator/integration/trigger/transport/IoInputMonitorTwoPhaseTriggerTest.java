package com.example.iml.orchestrator.integration.trigger.transport;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
import com.example.iml.orchestrator.integration.trigger.config.TwoPhaseTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.config.UdpTriggerConfig;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IoInputMonitorTwoPhaseTriggerTest {

    @Test
    void carriesTwoHardwarePulsesThroughTransportAndBusToSameTenCameras() throws Exception {
        List<Integer> cameraIds = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        Instant firstAt = Instant.parse("2026-08-19T07:00:00Z");
        try (InspectionTriggerBus bus = new InspectionTriggerBus(cameraIds);
             IoInputMonitorUdpTriggerTransport transport = new IoInputMonitorUdpTriggerTransport(
                     LogManager.getLogger(IoInputMonitorTwoPhaseTriggerTest.class),
                     UdpTriggerConfig.defaults(),
                     IoInputDiscreteConfig.defaults(),
                     new TwoPhaseTriggerConfig(true, 700, 150),
                     bus,
                     null,
                     List.of(),
                     null
             )) {
            assertEquals(10, transport.publishLineCapture(null, firstAt));
            assertEquals(10, transport.publishLineCapture(null, firstAt.plusMillis(120)));
            assertEquals(0, transport.publishLineCapture(null, firstAt.plusMillis(200)));

            for (int cameraId : cameraIds) {
                InspectionTriggerEvent phase0 = bus.take(cameraId);
                InspectionTriggerEvent phase1 = bus.take(cameraId);

                assertEquals(0, phase0.phaseId());
                assertEquals(phase0.rawTriggerSequence(), phase0.parentCycleId());
                assertEquals(phase0.rawTriggerSequence(), phase0.sequence());

                assertEquals(1, phase1.phaseId());
                assertEquals(phase0.parentCycleId(), phase1.parentCycleId());
                assertEquals(phase1.rawTriggerSequence(), phase1.sequence());
                assertEquals(phase0.rawTriggerSequence() + 1, phase1.rawTriggerSequence());
            }
        }
    }
}
