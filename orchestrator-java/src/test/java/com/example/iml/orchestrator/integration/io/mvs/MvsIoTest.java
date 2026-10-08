package com.example.iml.orchestrator.integration.io.mvs;

import com.example.iml.orchestrator.integration.trigger.*;
import com.example.iml.orchestrator.integration.trigger.config.*;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import com.example.iml.orchestrator.integration.trigger.transport.IoInputTriggerTransport;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class MvsIoTest {
    private static final org.apache.logging.log4j.Logger LOG = LogManager.getLogger(MvsIoTest.class);
    private static Map<String, Object> integration() {
        return Map.of("inspection_trigger", Map.of(
                "mvs_io", Map.of("enabled", true, "input_mode", "events"),
                "io_input", Map.of("di3_only", true, "direction_latch", false, "require_work", false,
                        "require_direction", true, "debounce_ms", 1000, "external_hardware_capture", false),
                "udp", Map.of("enabled", true, "format", "io_input")));
    }

    @Test void nativeModeDisablesLegacyProcessSelectionEvenIfUdpIsConfigured() {
        InspectionTriggerConfig cfg = InspectionTriggerConfig.parse(integration());
        assertTrue(cfg.mvsIo().enabled()); assertFalse(cfg.usesIoInputUdp());
        assertEquals(Map.of(5, "Out5"), cfg.mvsIo().outputs());
        assertFalse(MvsIoConfig.parse(null).enabled());
    }

    @Test void abiLayoutsMatchInstalledMvsHeaders() {
        assertEquals(2056, new JnaMvsIoDevice.DeviceList().size());
        assertEquals(280, new JnaMvsIoDevice.EnumValue().size());
        assertEquals(84, new JnaMvsIoDevice.EnumEntry().size());
    }

    @Test void twoTriggers80msApartAreAcceptedInSameDirectionWindowDespiteOldDebounceSetting() throws Exception {
        InspectionTriggerConfig cfg = InspectionTriggerConfig.parse(integration());
        try (InspectionTriggerBus bus = new InspectionTriggerBus(List.of(1));
             IoInputTriggerTransport processor = new IoInputTriggerTransport(
                     LOG, cfg.udp(), cfg.ioInput(), bus, null)) {
            AtomicInteger prefires = new AtomicInteger();
            bus.setLineTriggerListener((seq, at, cameras) -> prefires.incrementAndGet());
            processor.setHardwareObservationSource();
            processor.applyDiChange(new IoInputDiChange(2, true));
            processor.applyDiChange(new IoInputDiChange(3, true));
            processor.applyDiChange(new IoInputDiChange(3, false));
            Thread.sleep(80);
            processor.applyDiChange(new IoInputDiChange(3, true));
            assertEquals(0, prefires.get(), "hardware observation must never prefire cameras");
            assertNotEquals(bus.take(1).sequence(), bus.take(1).sequence());
            long observedSequence = bus.lastDispatchedSequence();
            processor.applyDiChange(new IoInputDiChange(2, false));
            processor.applyDiChange(new IoInputDiChange(3, false));
            processor.applyDiChange(new IoInputDiChange(3, true));
            assertEquals(observedSequence, bus.lastDispatchedSequence(), "direction gate must remain active");
            assertEquals(0, prefires.get());
        }
    }

    @Test void nativeFrameReceptionRequiresDi2EvenWhenDirectionFilteringIsDisabled() throws Exception {
        InspectionTriggerConfig cfg = InspectionTriggerConfig.parse(Map.of("inspection_trigger", Map.of(
                "mvs_io", Map.of("enabled", true),
                "io_input", Map.of("di3_only", true, "direction_latch", false,
                        "require_work", false, "require_direction", false),
                "udp", Map.of("enabled", true, "format", "io_input"))));
        try (InspectionTriggerBus bus = new InspectionTriggerBus(List.of(1));
             IoInputTriggerTransport processor = new IoInputTriggerTransport(
                     LOG, cfg.udp(), cfg.ioInput(), bus, null)) {
            processor.setHardwareObservationSource();
            processor.applyDiChange(new IoInputDiChange(2, false));
            processor.applyDiChange(new IoInputDiChange(3, true));
            assertEquals(0, bus.lastDispatchedSequence());
            processor.applyDiChange(new IoInputDiChange(3, false));
            processor.applyDiChange(new IoInputDiChange(2, true));
            processor.applyDiChange(new IoInputDiChange(3, true));
            assertTrue(bus.lastDispatchedSequence() > 0);
        }
    }

    @Test void fifoRetainsSecondTriggerWhileFirstCaptureIsBusyAndStartupHighDoesNotFire() throws Exception {
        MvsIoConfig cfg = InspectionTriggerConfig.parse(integration()).mvsIo();
        FakeDevice fake = new FakeDevice();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), captured = new CountDownLatch(2);
        AtomicInteger triggers = new AtomicInteger();
        try (MvsIoTransport transport = new MvsIoTransport(cfg, 3, LOG, () -> fake, change -> {
            if (change != null && change.diPort() == 3 && change.active()) {
                triggers.incrementAndGet(); entered.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                captured.countDown();
            }
        })) {
            transport.start(); assertTrue(fake.subscribed.await(2, TimeUnit.SECONDS));
            assertEquals(0, triggers.get(), "startup DI3=1 must not trigger capture");
            assertEquals(Map.of(5, true), transport.outputStates());
            fake.emit(3, true); assertTrue(entered.await(2, TimeUnit.SECONDS));
            fake.emit(3, false); fake.emit(3, true);
            release.countDown(); assertTrue(captured.await(2, TimeUnit.SECONDS));
            assertEquals(2, triggers.get());
        }
        assertTrue(fake.closed);
    }

    @Test void eventMappingsAreValidatedAndPreserveRepeatedRisingEvents() {
        var input = new MvsIoConfig.Input("In3", false, "DI3Positive", "DI3Negative");
        var events = JnaMvsIoDevice.resolveEvents(Map.of(3, input), List.of("DI3Positive", "DI3Negative"));
        assertEquals(new IoInputDiChange(3, true), events.get("DI3Positive"));
        assertEquals(new IoInputDiChange(3, false), events.get("DI3Negative"));
        assertThrows(IllegalStateException.class, () -> JnaMvsIoDevice.resolveEvents(Map.of(3, input), List.of("OtherEvent")));
        assertThrows(IllegalStateException.class, () -> JnaMvsIoDevice.resolveEvents(
                Map.of(3, new MvsIoConfig.Input("In3", false, "", "")), List.of("Line0RisingEdge", "Line0FallingEdge")));
    }

    private static class FakeDevice implements MvsIoDevice {
        final CountDownLatch subscribed = new CountDownLatch(1);
        volatile Consumer<IoInputDiChange> listener;
        volatile boolean closed;
        public void open() { }
        public Map<Integer, Boolean> readInputs() { return Map.of(1, true, 2, true, 3, true, 4, false); }
        public void subscribe(Consumer<IoInputDiChange> listener) { this.listener = listener; subscribed.countDown(); }
        public Map<Integer, Boolean> readOutputs() { return Map.of(5, true); }
        public void close() { closed = true; }
        void emit(int port, boolean active) { listener.accept(new IoInputDiChange(port, active)); }
    }
}
