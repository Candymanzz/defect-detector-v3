package com.example.iml.orchestrator.integration.trigger.transport;

import com.example.iml.orchestrator.integration.capture.DirectionCaptureWindow;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
import com.example.iml.orchestrator.integration.trigger.config.UdpTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IoInputDi3OnlyCaptureTest {
    private static final List<Integer> CAMERAS = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

    @Test
    void everyRisingNotificationReachesAllCamerasRegardlessOfDi2OrUiDirection() throws Exception {
        // Exercise the same processor used by UDP and by the native MVS source.
        for (boolean nativeSource : List.of(false, true)) {
            var direction = new ManualLineDirectionService();
            try (var bus = new InspectionTriggerBus(CAMERAS);
                 var transport = transport(bus, IoInputDiscreteConfig.defaults(), direction)) {
                var prefires = new AtomicInteger();
                var lightingEvents = new AtomicInteger();
                bus.setLineTriggerListener((seq, at, cameras) -> prefires.incrementAndGet());
                transport.addDiChangeListener(change -> {
                    if (change.diPort() == 2) lightingEvents.incrementAndGet();
                });
                if (nativeSource) transport.setHardwareObservationSource();

                transport.applyDiChange(new IoInputDiChange(1, false));
                fireAndCheckAllCameras(transport, bus, 1); // DI2 has never arrived.
                fireAndCheckAllCameras(transport, bus, 2); // Rising-only source, no DI3=0.
                transport.applyDiChange(new IoInputDiChange(2, false));
                direction.setDirection(ManualLineDirectionService.Direction.REVERSE);
                fireAndCheckAllCameras(transport, bus, 3);
                transport.applyDiChange(new IoInputDiChange(2, true));
                assertEquals(3, bus.lastDispatchedSequence(), "DI2 itself must not trigger capture");
                fireAndCheckAllCameras(transport, bus, 4);
                fireAndCheckAllCameras(transport, bus, 5);
                transport.applyDiChange(new IoInputDiChange(3, false));
                assertEquals(5, bus.lastDispatchedSequence(), "DI3 falling must not trigger capture");
                direction.setDirection(ManualLineDirectionService.Direction.FORWARD);
                fireAndCheckAllCameras(transport, bus, 6);
                assertEquals(2, lightingEvents.get(), "raw DI2 listeners still control lighting");
                assertEquals(0, prefires.get(), "hardware frame reception must never trigger cameras in software");
            }
        }
    }

    @Test
    void legacyDirectionWindowCannotReplaceDi3Trigger() throws Exception {
        try (var bus = new InspectionTriggerBus(CAMERAS);
             var transport = transport(bus, IoInputDiscreteConfig.defaults(), null)) {
            transport.setHardwareObservationSource();
            transport.setDirectionWindowCapture();
            assertFalse(DirectionCaptureWindow.enabled());
            transport.applyDiChange(new IoInputDiChange(2, true));
            transport.applyDiChange(new IoInputDiChange(2, false));
            assertEquals(0, bus.lastDispatchedSequence());
            fireAndCheckAllCameras(transport, bus, 1);
            transport.applyDiChange(null); // Reconnect must not require rearming DI2.
            assertEquals(1, bus.lastDispatchedSequence());
            fireAndCheckAllCameras(transport, bus, 2);
        }
    }

    @Test
    void explicitlyConfiguredWorkGateStillApplies() throws Exception {
        var cfg = IoInputDiscreteConfig.parse(Map.of("inspection_trigger", Map.of(
                "io_input", Map.of("di3_only", true, "require_work", true))), 0);
        try (var bus = new InspectionTriggerBus(CAMERAS);
             var transport = transport(bus, cfg, null)) {
            transport.applyDiChange(new IoInputDiChange(3, true));
            assertEquals(0, bus.lastDispatchedSequence());
            transport.applyDiChange(new IoInputDiChange(1, true));
            assertEquals(0, bus.lastDispatchedSequence());
            fireAndCheckAllCameras(transport, bus, 1);
        }
    }

    private static IoInputTriggerTransport transport(InspectionTriggerBus bus,
            IoInputDiscreteConfig cfg, ManualLineDirectionService direction) {
        return new IoInputTriggerTransport(LogManager.getLogger(IoInputDi3OnlyCaptureTest.class),
                UdpTriggerConfig.defaults(), cfg, bus, null, List.of(), direction);
    }

    private static void fireAndCheckAllCameras(IoInputTriggerTransport transport,
            InspectionTriggerBus bus, long sequence) throws Exception {
        transport.applyDiChange(new IoInputDiChange(3, true));
        assertEquals(sequence, bus.lastDispatchedSequence(), "each DI3 rising must dispatch immediately");
        for (int camera : CAMERAS) assertEquals(sequence, bus.take(camera).sequence());
    }
}
