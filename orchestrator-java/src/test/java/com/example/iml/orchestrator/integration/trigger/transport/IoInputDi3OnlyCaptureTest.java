package com.example.iml.orchestrator.integration.trigger.transport;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
import com.example.iml.orchestrator.integration.trigger.config.UdpTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises the actual transport handler without opening UDP sockets or contacting hardware. */
class IoInputDi3OnlyCaptureTest {
    private static final class Fixture implements AutoCloseable {
        final InspectionTriggerBus bus = new InspectionTriggerBus(IntStream.range(0, 10).boxed().toList());
        final ManualLineDirectionService direction = new ManualLineDirectionService();
        final IoInputMonitorUdpTriggerTransport transport;
        final Method apply;

        Fixture(boolean requireDirection, boolean requireWork) throws Exception {
            var config = IoInputDiscreteConfig.parse(Map.of("inspection_trigger", Map.of("io_input", Map.of(
                    "di3_only", true, "require_direction", requireDirection, "require_work", requireWork,
                    "direction_latch", false, "direction_latch_on_work", false,
                    "trigger_edge", "rising", "external_hardware_capture", true, "debounce_ms", 0))), 0);
            transport = new IoInputMonitorUdpTriggerTransport(LogManager.getLogger(getClass()),
                    new UdpTriggerConfig(false, "127.0.0.1", 0, "io_input", 0, 0, List.of()),
                    config, bus, null, List.of(), direction);
            apply = IoInputMonitorUdpTriggerTransport.class.getDeclaredMethod("applyDiChange", IoInputDiChange.class);
            apply.setAccessible(true);
        }

        void di(int port, boolean high) throws Exception { apply.invoke(transport, new IoInputDiChange(port, high)); }
        void expectCapture() throws Exception {
            long previous = bus.currentSequence();
            di(3, true);
            assertEquals(previous + 1, bus.lastDispatchedSequence());
            assertEquals(10, bus.clearAllPending(), "one trigger per camera, not a duplicated exposure");
        }
        @Override public void close() { transport.close(); bus.close(); }
    }

    @Test void everyDi3CapturesWhenDi2StaysHigh() throws Exception {
        try (var f = new Fixture(false, false)) {
            f.di(2, true);
            for (int i = 0; i < 5; i++) f.expectCapture(); // Rising-only: no DI3=0 packets.
        }
    }

    @Test void everyDi3CapturesWhenDi2IsLowOrUnknown() throws Exception {
        try (var f = new Fixture(false, false)) {
            f.expectCapture(); // No DI1/DI2 state received yet.
            f.di(2, false);
            f.expectCapture();
            f.di(3, false);
            f.expectCapture();
        }
    }

    @Test void workDirectionAndFallingEdgesDoNotStartCapture() throws Exception {
        try (var f = new Fixture(false, false)) {
            f.di(1, true); f.di(2, true); f.di(1, false); f.di(2, false); f.di(3, false);
            assertEquals(0, f.bus.clearAllPending());
            f.expectCapture();
        }
    }

    @Test void uiDirectionDoesNotRestrictDi3Mode() throws Exception {
        try (var f = new Fixture(false, false)) {
            f.direction.setDirection(ManualLineDirectionService.Direction.REVERSE);
            f.di(2, false);
            f.expectCapture(); f.expectCapture();
            f.di(2, true);
            f.expectCapture();
        }
    }

    @Test void explicitDirectionGateKeepsItsPreviousOneShotBehavior() throws Exception {
        try (var f = new Fixture(true, false)) {
            f.di(3, true);
            assertEquals(0, f.bus.clearAllPending());
            f.di(2, true);
            f.expectCapture();
            f.di(3, true);
            assertEquals(0, f.bus.clearAllPending());
            f.di(2, false); f.di(2, true);
            f.expectCapture();
        }
    }

    @Test void explicitWorkGateStillRequiresDi1() throws Exception {
        try (var f = new Fixture(false, true)) {
            f.di(3, true);
            assertEquals(0, f.bus.clearAllPending());
            f.di(1, true);
            f.expectCapture();
        }
    }

    @Test void healthGateAndShutdownNotificationsArePreserved() throws Exception {
        try (var f = new Fixture(false, false)) {
            f.bus.setDispatchAllowed(() -> false);
            f.di(3, true);
            assertEquals(0, f.bus.clearAllPending());
            f.bus.setDispatchAllowed(() -> true);
            f.expectCapture();
            var shutdown = new java.util.concurrent.atomic.AtomicInteger();
            f.transport.addDiChangeListener(change -> {
                if (change.diPort() == 4 && change.active()) shutdown.incrementAndGet();
            });
            f.di(4, true);
            assertEquals(1, shutdown.get());
            assertEquals(0, f.bus.clearAllPending());
        }
    }
}
