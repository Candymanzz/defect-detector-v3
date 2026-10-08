package com.example.iml.orchestrator.integration.io.mvs;

import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MvIoEventsTest {
    private static final org.apache.logging.log4j.Logger LOG = LogManager.getLogger(MvIoEventsTest.class);
    private static MvsIoConfig config(Map<String, Object> overrides) {
        Map<String, Object> io = new HashMap<>(Map.of("enabled", true, "backend", "mv_io",
                "library", "fake-io-sdk", "port", "COM42", "health_check_ms", 10,
                "outputs", Map.of(), "inputs", Map.of(
                        2, Map.of("port", 2, "edge", "both"), 3, Map.of("port", 3, "edge", "rising"))));
        io.putAll(overrides);
        return MvsIoConfig.parse(Map.of("inspection_trigger", Map.of("mvs_io", io)));
    }
    @Test void ioAbiMatchesExistingPInvokeDefinitions() {
        assertEquals(96, new JnaMvIoDevice.Serial().size());
        assertEquals(52, new JnaMvIoDevice.InputParam().size());
        assertEquals(44, new JnaMvIoDevice.Levels().size());
        var edge = new JnaMvIoDevice.Edge();
        assertEquals(40, edge.size());
        edge.port = 4; edge.times = 9; edge.edge = 1; edge.write();
        assertEquals(9, edge.getPointer().getShort(2));
        assertEquals(1, edge.getPointer().getInt(4));
        assertEquals(56, new JnaMvIoDevice.Version().size());
    }
    @Test void callbacksDeliverRepeatedRisingAndBothDirectionEdgesWithoutOutputCommands() throws Exception {
        FakeSdk sdk = new FakeSdk();
        BlockingQueue<IoInputDiChange> events = new LinkedBlockingQueue<>();
        try (var device = new JnaMvIoDevice(config(Map.of()), LOG, sdk)) {
            device.open(); assertEquals(Map.of(2, true, 3, false), device.readInputs());
            device.subscribe(events::add);
            sdk.emit(3, 1); sdk.emit(3, 1); sdk.emit(2, 2); sdk.emit(2, 1); sdk.emit(4, 1);
            assertEquals(new IoInputDiChange(3, true), events.poll(2, TimeUnit.SECONDS));
            assertEquals(new IoInputDiChange(3, true), events.poll(2, TimeUnit.SECONDS));
            assertEquals(new IoInputDiChange(2, false), events.poll(2, TimeUnit.SECONDS));
            assertEquals(new IoInputDiChange(2, true), events.poll(2, TimeUnit.SECONDS));
            assertNull(events.poll(30, TimeUnit.MILLISECONDS));
            device.checkConnection();
            assertTrue(sdk.arms.stream().filter(a -> a.enable() == 1 && a.glitch() == 0)
                    .allMatch(a -> a.delay() == 0));
            assertEquals(List.of(1, 1, 1), sdk.arms.stream().filter(a -> a.port() == 4).map(Arm::edge).toList());
            assertEquals(List.of(2, 1, 2), sdk.arms.stream().filter(a -> a.port() == 2).map(Arm::edge).toList());
        }
        assertEquals(2, sdk.arms.stream().filter(a -> a.glitch() == 17 && a.delay() == 7).count());
        assertTrue(sdk.calls.contains("MV_IO_Close"));
        assertTrue(sdk.calls.contains("MV_IO_DestroyHandle"));
        assertTrue(sdk.calls.stream().noneMatch(name -> name.contains("Output") || name.contains("Timer") || name.contains("TriggerSoftware")));
    }
    @Test void eventTransportChecksHealthWithoutPollingDiLevels() throws Exception {
        FakeSdk sdk = new FakeSdk();
        CountDownLatch triggered = new CountDownLatch(2);
        var cfg = config(Map.of());
        try (var transport = new MvsIoTransport(cfg, 3, LOG, () -> new JnaMvIoDevice(cfg, LOG, sdk), change -> {
            if (change != null && change.diPort() == 3 && change.active()) triggered.countDown();
        })) {
            transport.start(); assertTrue(sdk.registered.await(2, TimeUnit.SECONDS));
            // Callback registration happens before input setup, so wait for firmware health check.
            assertTrue(sdk.health.await(2, TimeUnit.SECONDS));
            int reads = sdk.levelReads.get();
            sdk.emit(3, 1); sdk.emit(3, 1);
            assertTrue(triggered.await(2, TimeUnit.SECONDS));
            Thread.sleep(40);
            assertEquals(reads, sdk.levelReads.get(), "event mode must not poll DI");
        }
    }
    @Test void physicalPortMappingAndInversionComeFromConfig() throws Exception {
        var cfg = config(Map.of("inputs", Map.of(5, Map.of("port", 2, "edge", "both", "invert", true))));
        FakeSdk sdk = new FakeSdk(); BlockingQueue<IoInputDiChange> events = new LinkedBlockingQueue<>();
        try(var device = new JnaMvIoDevice(cfg, LOG, sdk)) {
            device.open(); assertEquals(Map.of(5, false), device.readInputs());
            device.subscribe(events::add); sdk.emit(2, 2);
            assertEquals(new IoInputDiChange(5, true), events.poll(2, TimeUnit.SECONDS));
        }
    }
    @Test void rejectsUnsupportedModesMissingSdkSettingsAndUnverifiedDoReads() {
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("input_mode", "poll")));
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("library", "")));
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("inputs", Map.of(2, Map.of("edge", "invalid")))));
        var cfg = config(Map.of("outputs", Map.of(5, "Out5")));
        try(var device = new JnaMvIoDevice(cfg, LOG, new FakeSdk())) {
            assertThrows(IllegalArgumentException.class, device::open);
        }
    }
    @Test void callbackRearmFailureBecomesConnectionFailureAndSettingsAreRestored() throws Exception {
        FakeSdk sdk = new FakeSdk(); var cfg = config(Map.of());
        try(var device = new JnaMvIoDevice(cfg, LOG, sdk)) {
            device.open(); device.subscribe(change -> { }); sdk.failRearm = true; sdk.emit(3, 1);
            assertTrue(sdk.failed.await(2, TimeUnit.SECONDS));
            // The failure is recorded just after the fake SDK returns its error.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean rejected = false;
            while (!rejected && System.nanoTime() < deadline) {
                try { device.checkConnection(); } catch (IllegalStateException e) { rejected = true; }
                if (!rejected) Thread.sleep(1);
            }
            assertTrue(rejected);
        }
        assertTrue(sdk.calls.contains("MV_IO_DestroyHandle"));
    }
    @Test void onlyConfiguredRejectOutputsAreWrittenWithExplicitSdkEncoding() {
        var cfg = MvsIoConfig.parse(Map.of("inspection_trigger", Map.of("mvs_io", Map.of(
                "enabled", true, "backend", "mv_io", "library", "fake", "port", "COM42",
                "inputs", Map.of(2, Map.of("port", 2)))),
                "io_reject", Map.of("enabled", true, "groups", Map.of(
                        0, Map.of("port", 6, "sdk_port", 5), 1, Map.of("port", 7, "sdk_port", 6)))));
        FakeSdk sdk = new FakeSdk();
        try(var device = new JnaMvIoDevice(cfg, LOG, sdk)) {
            device.open(); assertTrue(sdk.outputWrites.isEmpty(), "opening/checking IO must not drive outputs");
            device.setOutput(6, true); device.setOutput(7, true); device.setOutput(6, false);
            assertThrows(IllegalArgumentException.class, () -> device.setOutput(5, true));
            assertEquals(List.of("5:1", "6:1", "5:0"), sdk.outputWrites);
        }
        assertEquals(5, sdk.outputWrites.size(), "close must reset both initialized outputs");
        assertEquals(40, new JnaMvIoDevice.OutputLevel().size());
        assertEquals(40, new JnaMvIoDevice.OutputEnable().size());
    }
    private record Arm(int port, int enable, int edge, int delay, int glitch) { }
    private static final class FakeSdk implements JnaMvIoDevice.Calls {
        final List<String> outputWrites = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<Arm> arms = new CopyOnWriteArrayList<>();
        final AtomicInteger levelReads = new AtomicInteger();
        final CountDownLatch registered = new CountDownLatch(1), health = new CountDownLatch(1), failed = new CountDownLatch(1);
        volatile JnaMvIoDevice.EdgeCallback callback;
        volatile boolean failRearm;
        public int invoke(String name, Object... args) {
            calls.add(name);
            switch (name) {
                case "MV_IO_CreateHandle" -> ((PointerByReference) args[0]).setValue(new Pointer(123));
                case "MV_IO_Open" -> assertTrue(new String(((JnaMvIoDevice.Serial)args[1]).comName).startsWith("COM42"));
                case "MV_IO_GetInputLevel" -> {
                    levelReads.incrementAndGet();
                    var levels = (JnaMvIoDevice.Levels)args[1]; levels.levels[1] = 1; levels.write();
                }
                case "MV_IO_RegisterEdgeDetectionCallBack" -> {
                    callback = (JnaMvIoDevice.EdgeCallback)args[1]; registered.countDown();
                }
                case "MV_IO_GetPortInputParam" -> {
                    var input = (JnaMvIoDevice.InputParam)args[1]; input.enable = 1; input.edge = 2;
                    input.delay = 7; input.glitch = 17; input.write();
                }
                case "MV_IO_SetInput" -> {
                    var input = (JnaMvIoDevice.InputParam)args[1];
                    arms.add(new Arm(input.port, input.enable, input.edge, input.delay, input.glitch));
                    if (failRearm && input.glitch == 0) { failed.countDown(); return 42; }
                }
                case "MV_IO_SetOutputEnable" -> assertEquals(0, ((JnaMvIoDevice.OutputEnable)args[1]).enable);
                case "MV_IO_SetMainOutputLevel" -> {
                    var level = (JnaMvIoDevice.OutputLevel)args[1]; outputWrites.add(level.port + ":" + level.status);
                }
                case "MV_IO_GetFirmwareVersion" -> health.countDown();
                case "MV_IO_Close", "MV_IO_DestroyHandle" -> { }
                default -> fail("Unexpected SDK command " + name);
            }
            return 0;
        }
        void emit(int port, int edgeType) {
            var edge = new JnaMvIoDevice.Edge(); edge.port = (byte)(1 << (port - 1)); edge.edge = edgeType; edge.write();
            callback.invoke(new Pointer(123), edge.getPointer(), null);
        }
    }
}
