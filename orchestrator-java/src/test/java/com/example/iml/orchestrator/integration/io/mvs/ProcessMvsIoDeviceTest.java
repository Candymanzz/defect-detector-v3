package com.example.iml.orchestrator.integration.io.mvs;

import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ProcessMvsIoDeviceTest {
    private static final org.apache.logging.log4j.Logger LOG = LogManager.getLogger(ProcessMvsIoDeviceTest.class);
    private static MvsIoConfig config() {
        return MvsIoConfig.parse(Map.of("inspection_trigger", Map.of("mvs_io", Map.of(
                "enabled", true, "input_mode", "poll", "sdk_timeout_ms", 200,
                "reconnect_ms", 10, "shutdown_timeout_ms", 500, "outputs", Map.of()))));
    }
    private static ProcessMvsIoDevice worker(String mode) {
        return new ProcessMvsIoDevice(config(), LOG, new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), FakeWorker.class.getName(), mode));
    }
    @Test void configurationSurvivesWorkerProtocol() throws Exception {
        var original = config();
        assertEquals(original, MvsIoWorkerMain.JSON.readValue(
                MvsIoWorkerMain.JSON.writeValueAsString(original), MvsIoConfig.class));
    }
    @Test void hungReadKillsWorkerAndRejectsFurtherCommands() {
        try (var device = worker("inputs")) {
            device.open();
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                    assertThrows(IllegalStateException.class, device::readInputs));
            assertThrows(IllegalStateException.class, () -> device.setOutput(3, true));
        }
    }
    @Test void hungOutputAndHungCloseAreBounded() {
        try (var device = worker("setAll")) {
            device.open();
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                    assertThrows(IllegalStateException.class, () -> device.setOutputs(Map.of(3, true, 4, false))));
        }
        var device = worker("close");
        device.open();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                assertThrows(IllegalStateException.class, device::close));
    }
    @Test void crashedWorkerFailsPromptly() {
        try (var device = worker("crash")) {
            device.open();
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                    assertThrows(IllegalStateException.class, device::readInputs));
        }
    }
    @Test void eventCallbacksAndOutputCommandsCrossProcessBoundary() throws Exception {
        try (var device = worker("normal")) {
            device.open();
            var events = new LinkedBlockingQueue<IoInputDiChange>();
            device.subscribe(events::add);
            assertEquals(new IoInputDiChange(3, true), events.poll(2, TimeUnit.SECONDS));
            assertEquals(Map.of(2, false, 3, true), device.readInputs());
            device.setOutputs(Map.of(3, true, 4, false));
            device.triggerTimer("Timer2", "Out2");
        }
    }
    @Test void transportReconnectsAfterHungSdkWithoutSyntheticTriggerEdge() throws Exception {
        var attempts = new AtomicInteger();
        var seededTrigger = new LinkedBlockingQueue<IoInputDiChange>();
        try (var transport = new MvsIoTransport(config(), 3, LOG,
                () -> worker(attempts.incrementAndGet() == 1 ? "inputs" : "normal"),
                change -> { if (change != null && change.diPort() == 3) seededTrigger.add(change); })) {
            transport.start();
            assertEquals(new IoInputDiChange(3, false), seededTrigger.poll(8, TimeUnit.SECONDS));
            assertTrue(transport.isConnected());
            assertEquals(2, attempts.get());
            assertNull(seededTrigger.poll(100, TimeUnit.MILLISECONDS));
        }
    }
    /** Separate JVM that simulates a native call which never returns. */
    public static class FakeWorker {
        public static void main(String[] args) throws Exception {
            var input = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = input.readLine()) != null) {
                var request = MvsIoWorkerMain.JSON.readTree(line);
                String op = request.path("op").asText();
                if (op.equals(args[0])) new CountDownLatch(1).await();
                if (args[0].equals("crash") && op.equals("inputs")) Runtime.getRuntime().halt(2);
                if (op.equals("subscribe")) System.out.println(MvsIoWorkerMain.PREFIX + "{\"event\":{\"diPort\":3,\"active\":true}}");
                System.out.println("vendor SDK diagnostic");
                System.out.println(MvsIoWorkerMain.PREFIX + (op.equals("inputs") ? "{\"ok\":true,\"result\":{\"2\":false,\"3\":true}}"
                        : "{\"ok\":true,\"result\":{}}"));
                System.out.flush();
                if (op.equals("close")) return;
            }
        }
    }
}
