package com.example.iml.orchestrator.integration.camera;

import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.service.CameraWorkerBootstrapService;
import com.example.iml.orchestrator.integration.bootstrap.service.IntegrationPreflightService;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real JNI round trips and shared-memory writes, without camera hardware. */
class NativeCameraClientTest {
    @TempDir Path directory;
    private Path library;
    private Path config;

    @BeforeEach
    void configureFakeCameras() throws IOException {
        String configured = System.getProperty("iml.camera.native.library", "");
        assumeTrue(!configured.isBlank(), "Set iml.camera.native.library to run JNI tests");
        library = Path.of(configured);
        assertTrue(Files.isRegularFile(library), "Configured JNI library must exist");
        config = directory.resolve("config.json");
        Files.writeString(config, """
                {"capture_source":"fake","frame":{"width":8,"height":6,"format":"BGR8"},
                 "cameras":[{"id":30001,"ip":"127.0.0.1","exposure_us":1500,"capture_trigger_mode":"software"},
                            {"id":30002,"ip":"127.0.0.2","exposure_us":2500,"capture_trigger_mode":"line0"}]}
                """);
    }

    @Test
    void writesFramesToTheExistingSharedMemoryRing() throws Exception {
        try (NativeCameraClient camera = camera(30001)) {
            Map<String, Object> frame = null;
            for (int i = 1; i <= 10; i++) {
                frame = ok(camera, Map.of("op", "capture", "sync", true));
                assertEquals(i, number(frame, "frame_id"));
                assertEquals((i - 1) % 8, number(frame, "slot_index"));
            }
            assertEquals(8, number(frame, "width"));
            assertEquals(6, number(frame, "height"));
            assertEquals(24, number(frame, "stride"));
            assertEquals("BGR8", frame.get("format"));
            String name = frame.get("shm_name").toString().substring(1);
            Path shm = System.getProperty("os.name").startsWith("Windows")
                    ? Path.of(System.getenv("LOCALAPPDATA"), "iml_shm", name) : Path.of("/dev/shm", name);
            try (FileChannel channel = FileChannel.open(shm, StandardOpenOption.READ)) {
                ByteBuffer pixels = ByteBuffer.allocate(144);
                assertEquals(144, channel.read(pixels, number(frame, "shm_offset")));
                assertEquals(10, pixels.get(1) & 0xff);
            }
            assertEquals(10, number(ok(camera, Map.of("op", "health")), "capture_total"));
        }
    }

    @Test
    void settingsAndStreamingKeepTheirExistingBehavior() throws Exception {
        try (NativeCameraClient camera = camera(30001)) {
            Map<String, Object> settings = ok(camera, Map.of("op", "set_settings", "exposure_us", 4500, "gain_db", 2.5));
            assertEquals(4500, number(settings, "exposure_us"));
            assertEquals(2.5, ((Number)settings.get("gain_db")).doubleValue());
            ok(camera, Map.of("op", "start_stream", "fps", 30));
            assertEquals(BinaryProtocol.MSG_ERROR, camera.command(Map.of("op", "capture")).type());
            assertEquals(BinaryProtocol.MSG_ERROR, camera.command(Map.of("op", "set_settings", "capture_trigger_mode", "line0")).type());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            BinaryProtocol.Message frame;
            do {
                frame = camera.command(Map.of("op", "stream_poll"));
                if (frame.type() == BinaryProtocol.MSG_RESPONSE) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertEquals(BinaryProtocol.MSG_RESPONSE, frame.type());
            assertEquals(true, frame.header().get("streaming"));
            ok(camera, Map.of("op", "stop_stream"));
            assertEquals("software", ok(camera, Map.of("op", "get_settings")).get("capture_trigger_mode"));
            ok(camera, Map.of("op", "capture", "sync", true));
        }
    }

    @Test
    void decimalJsonIgnoresTheCallingThreadsLocaleAndRestoresIt() throws Exception {
        assumeTrue(Platform.isLinux(), "POSIX thread-locale regression");
        NativeLibrary libc = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME);
        Pointer commaLocale = null;
        for (String name : List.of("ru_RU.UTF-8", "de_DE.UTF-8", "fr_FR.UTF-8")) {
            // Linux LC_NUMERIC=1; the newlocale mask is 1 << LC_NUMERIC.
            commaLocale = libc.getFunction("newlocale").invokePointer(new Object[]{2, name, Pointer.NULL});
            if (commaLocale != null) break;
        }
        assumeTrue(commaLocale != null, "Install a locale with a comma decimal separator");
        Pointer previous = libc.getFunction("uselocale").invokePointer(new Object[]{commaLocale});
        assertNotNull(previous);
        try (NativeCameraClient camera = camera(30001)) {
            Map<String, Object> settings = ok(camera, Map.of("op", "set_settings", "gain_db", 2.5, "gamma", 1.25));
            assertEquals(2.5, ((Number)settings.get("gain_db")).doubleValue());
            assertEquals(1.25, ((Number)settings.get("gamma")).doubleValue());
            assertEquals("ok", ok(camera, Map.of("op", "health")).get("status"));
            assertEquals(commaLocale, libc.getFunction("uselocale").invokePointer(new Object[]{Pointer.NULL}));
        } finally {
            libc.getFunction("uselocale").invokePointer(new Object[]{previous});
            libc.getFunction("freelocale").invokeVoid(new Object[]{commaLocale});
        }
    }

    @Test
    void camerasCaptureInParallelAndClosingOneKeepsTheOtherAlive() throws Exception {
        try (NativeCameraClient first = camera(30001); NativeCameraClient second = camera(30002)) {
            assertThrows(IOException.class, () -> camera(30001));
            var executor = Executors.newFixedThreadPool(2);
            try {
                var a = executor.submit(() -> captureMany(first));
                var b = executor.submit(() -> captureMany(second));
                a.get(5, TimeUnit.SECONDS);
                b.get(5, TimeUnit.SECONDS);
                assertEquals(50, number(ok(first, Map.of("op", "health")), "capture_total"));
                assertEquals(50, number(ok(second, Map.of("op", "health")), "capture_total"));
            } finally { executor.shutdownNow(); }
            first.close();
            first.close();
            assertFalse(first.isAlive());
            assertThrows(IOException.class, () -> first.command(Map.of("op", "health")));
            assertEquals(51, number(ok(second, Map.of("op", "capture")), "frame_id"));
            try (NativeCameraClient reopened = camera(30001)) {
                assertEquals(1, number(ok(reopened, Map.of("op", "capture")), "frame_id"));
            }
        }
    }

    @Test
    void supervisorRestartsTheNativeCameraWithoutLaunchingAWorker() throws Exception {
        WorkerProcessSupervisor supervisor = new WorkerProcessSupervisor(30001, library, config, 1000);
        try {
            supervisor.start();
            assertTrue(supervisor.processAlive());
            assertEquals(BinaryProtocol.MSG_RESPONSE, supervisor.health().type());
            supervisor.command(Map.of("op", "capture"));
            supervisor.restart();
            assertEquals(1, supervisor.restartCount());
            assertEquals(0, number(supervisor.health().header(), "capture_total"));
        } finally { supervisor.close(); }
        assertFalse(supervisor.processAlive());
    }

    @Test
    void processFaultInjectionCannotTerminateOrBlockTheJvm() throws Exception {
        try (NativeCameraClient camera = camera(30001)) {
            for (String op : new String[]{"inject_exit", "inject_timeout_ms", "inject_broken_response"})
                assertEquals(BinaryProtocol.MSG_ERROR, camera.command(Map.of("op", op)).type());
            assertEquals("ok", ok(camera, Map.of("op", "health")).get("status"));
        }
    }

    @Test
    void bootstrapUsesJniWithoutAWorkerExecutableEvenWithLegacyStdioConfig() {
        var integration = Map.<String, Object>of("worker_config_json", config.toString(), "worker_ipc_mode", "stdio");
        var root = Map.<String, Object>of("integration", integration, "cameras", List.of(
                Map.of("id", 30001, "enabled", true), Map.of("id", 30002, "enabled", true)));
        var log = LogManager.getLogger(NativeCameraClientTest.class);
        var context = new IntegrationRuntimeContext(log, root, directory, System.getProperty("os.name").startsWith("Windows"));
        try {
            assertTrue(new IntegrationPreflightService(log).run(context));
            assertEquals(library.toAbsolutePath(), context.cameraNativeLibrary());
            assertTrue(new CameraWorkerBootstrapService(log).startWorkers(context));
            assertEquals(2, context.workersByCamera().size());
            assertTrue(context.workersByCamera().values().stream().allMatch(WorkerProcessSupervisor::processAlive));
        } finally { context.workersByCamera().values().forEach(WorkerProcessSupervisor::close); }
    }

    @Test
    void closesAnActiveStreamBeforeReopeningTheCamera() throws Exception {
        NativeCameraClient camera = camera(30001);
        try {
            ok(camera, Map.of("op", "start_stream", "fps", 30));
            camera.requestStop();
            assertFalse(camera.isAlive());
        } finally { camera.close(); }
        try (NativeCameraClient reopened = camera(30001)) {
            assertEquals("software", ok(reopened, Map.of("op", "get_settings")).get("capture_trigger_mode"));
            ok(reopened, Map.of("op", "capture"));
        }
    }

    @Test
    void failedInitializationDoesNotReserveTheCameraId() throws Exception {
        Files.writeString(config, "{\"capture_source\":\"unsupported\",\"cameras\":[{\"id\":30001}]}");
        assertThrows(IOException.class, () -> camera(30001));
        configureFakeCameras();
        try (NativeCameraClient camera = camera(30001)) {
            assertEquals("ok", ok(camera, Map.of("op", "health")).get("status"));
        }
    }

    private NativeCameraClient camera(int id) throws IOException { return new NativeCameraClient(id, library, config); }
    private static long number(Map<String, Object> values, String key) { return ((Number)values.get(key)).longValue(); }
    private static Map<String, Object> ok(NativeCameraClient camera, Map<String, Object> command) throws IOException {
        BinaryProtocol.Message response = camera.command(command);
        assertEquals(BinaryProtocol.MSG_RESPONSE, response.type(), response.header().toString());
        return response.header();
    }
    private static Void captureMany(NativeCameraClient camera) throws IOException {
        for (int i = 0; i < 50; i++) ok(camera, Map.of("op", "capture"));
        return null;
    }
}
