package com.example.iml.orchestrator.integration.io.mvs;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class IoRejectPublisherTest {
    private record Write(int port, boolean high, long time) { }
    private static IoRejectConfig config(int pulse) {
        return IoRejectConfig.parse(Map.of("io_reject", Map.of("enabled", true, "pulse_ms", pulse,
                "groups", Map.of(0, Map.of("port", 6), 1, Map.of("port", 7)))));
    }
    @Test void twoGroupsPulseIndependentlyAndRepeatedPulsesKeepTheirEdges() throws Exception {
        List<Write> writes = new CopyOnWriteArrayList<>(); CountDownLatch ended = new CountDownLatch(3);
        try(var publisher = new IoRejectPublisher(config(80))) {
            publisher.bind((port, high) -> { writes.add(new Write(port, high, System.nanoTime())); if (!high) ended.countDown(); });
            publisher.publish(0, 1, false, false); publisher.publish(1, 1, false, false);
            publisher.publish(0, 2, false, false);
            assertTrue(ended.await(2, TimeUnit.SECONDS));
            var six = writes.stream().filter(w -> w.port() == 6).toList();
            var seven = writes.stream().filter(w -> w.port() == 7).toList();
            assertEquals(List.of(true, false, true, false), six.stream().map(Write::high).toList());
            assertEquals(List.of(true, false), seven.stream().map(Write::high).toList());
            assertTrue(seven.get(0).time() < six.get(1).time(), "DO7 must activate while DO6 is still pulsing");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(six.get(1).time() - six.get(0).time()) >= 70);
        }
    }
    @Test void plasticHoldIsStableUntilPassAndPolarityIsConfigurable() {
        var cfg = IoRejectConfig.parse(Map.of("io_reject", Map.of("enabled", true,
                "groups", Map.of(0, Map.of("port", 7, "active_high", false)))));
        List<Boolean> writes = new CopyOnWriteArrayList<>();
        try(var publisher = new IoRejectPublisher(cfg)) {
            publisher.bind((port, high) -> writes.add(high));
            publisher.publish(0, 1, false, true); publisher.publish(0, 2, false, true);
            publisher.publish(0, 3, true, true); publisher.publish(0, 4, true, true);
            assertEquals(List.of(false, true), writes);
        }
    }
    @Test void missingConnectionAndSdkFailureAreReportedWithoutFallback() {
        try(var publisher = new IoRejectPublisher(config(80))) {
            assertThrows(IllegalStateException.class, () -> publisher.publish(0, 1, false, false));
            publisher.bind((port, high) -> { throw new IllegalStateException("SDK is disconnected"); });
            assertThrows(IllegalStateException.class, () -> publisher.publish(0, 1, false, false));
        }
    }
    @Test void hardwareTimersPulseForEachRejectWithoutLevelWritesOrPassCommands() {
        var cfg = IoRejectConfig.parse(Map.of("io_reject", Map.of("enabled", true, "mode", "timer",
                "pulse_ms", 10000, "groups", Map.of(0, Map.of("port", 6, "timer", "Timer6"), 1, Map.of("port", 7, "timer", "Timer7")))));
        List<String> commands = new CopyOnWriteArrayList<>();
        try (var publisher = new IoRejectPublisher(cfg)) {
            publisher.bind((port, high) -> fail("Timer mode must never write DO levels"));
            publisher.bindTimer((timer, line) -> commands.add(timer + ":" + line));
            publisher.publish(0, 1, false, false);
            publisher.publish(0, 2, false, false);
            publisher.publish(1, 2, false, false);
            publisher.publish(0, 3, true, false);
            publisher.rejectAll(4);
            publisher.publish(0, 4, false, true);
            publisher.publish(1, 4, false, true);
            assertEquals(5, commands.size());
            assertEquals(List.of("Timer6:Out6", "Timer6:Out6", "Timer7:Out7"), commands.subList(0, 3));
        }
        assertEquals(5, commands.size(), "Shutdown must not trigger a timer");
    }
    @Test void timerFailureIsReportedAndCameraTimerIsProtected() {
        var cfg = IoRejectConfig.parse(Map.of("io_reject", Map.of("enabled", true, "mode", "timer",
                "groups", Map.of(0, Map.of("port", 6, "timer", "Timer6")))));
        try (var publisher = new IoRejectPublisher(cfg)) {
            publisher.bindTimer((timer, line) -> { throw new IllegalStateException("SDK failed"); });
            assertThrows(IllegalStateException.class, () -> publisher.publish(0, 1, false, false));
        }
        assertThrows(IllegalArgumentException.class, () -> IoRejectConfig.parse(Map.of("io_reject", Map.of("enabled", true,
                "mode", "timer", "groups", Map.of(0, Map.of("port", 6, "timer", "Timer5"))))));
    }
    @Test void cameraOutputIsProtectedAndDuplicateAssignmentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> IoRejectConfig.parse(Map.of("io_reject", Map.of(
                "enabled", true, "groups", Map.of(0, Map.of("port", 5))))));
        assertThrows(IllegalArgumentException.class, () -> IoRejectConfig.parse(Map.of("io_reject", Map.of(
                "enabled", true, "groups", Map.of(0, Map.of("port", 6), 1, Map.of("port", 6))))));
    }
}
