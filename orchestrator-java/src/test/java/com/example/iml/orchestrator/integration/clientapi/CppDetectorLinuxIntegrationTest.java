package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.example.iml.orchestrator.integration.http.HttpRequestContext;
import com.example.iml.orchestrator.integration.http.controller.OrchestratorDetectorSettingsHttpController;
import com.sun.net.httpserver.HttpServer;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Java client -> local C++ HTTP server -> synthetic /dev/shm frames; no cameras or PLC. */
@EnabledOnOs(OS.LINUX)
class CppDetectorLinuxIntegrationTest {
    @TempDir Path temporary;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void concurrentCamerasSettingsHeatmapAndLostReferenceRecovery() throws Exception {
        String executable = System.getProperty("iml.cpp.detector.binary");
        assumeTrue(executable != null && Files.isExecutable(Path.of(executable)),
                "Set -Diml.cpp.detector.binary to a built Linux detector");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        String base = "http://127.0.0.1:" + port;
        ProcessBuilder launch = new ProcessBuilder(executable, "--port", String.valueOf(port), "--workers", "2",
                "--data-dir", temporary.resolve("data").toString(), "--log-dir", temporary.resolve("logs").toString());
        launch.environment().put("CAS_PERFORMANCE_LOG_DIR", temporary.resolve("logs").toString());
        Process process = launch.redirectErrorStream(true).redirectOutput(temporary.resolve("server.log").toFile()).start();
        List<Path> buffers = new ArrayList<>();
        var executor = Executors.newFixedThreadPool(10);
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var controller = new OrchestratorDetectorSettingsHttpController(base, Map.of(0, "bench-linux-0"));
        proxy.createContext("/api/orchestrator/detector-settings", exchange -> controller.handle(new HttpRequestContext(exchange)));
        proxy.setExecutor(executor);
        proxy.start();
        String settingsBase = "http://127.0.0.1:" + proxy.getAddress().getPort();
        try {
            var client = new CppAnalisSurfaceHttpSupervisor("linux-smoke", base, 5000);
            boolean healthy = false;
            for (int i = 0; i < 100 && process.isAlive(); i++) {
                try { client.health(); healthy = true; break; }
                catch (Exception ignored) { Thread.sleep(50); }
            }
            assertTrue(healthy, () -> "C++ did not start: " + readLog());
            // The multipart API differs between Ubuntu httplib and newer vcpkg builds.
            BufferedImage image = new BufferedImage(96, 80, BufferedImage.TYPE_3BYTE_BGR);
            for (int y = 0; y < 80; y++) for (int x = 0; x < 96; x++)
                image.setRGB(x, y, ((x / 8 + y / 8) % 2 == 0) ? 0x808080 : 0xc0c0c0);
            var png = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, "png", png));
            assertEquals(200, multipart(base, "/upload-ref", png.toByteArray()).statusCode());
            var manual = multipart(base, "/inspect", png.toByteArray());
            assertEquals(200, manual.statusCode(), manual.body());
            assertEquals("ГОДЕН", JSON.readTree(manual.body()).get("status").asText());
            int width = 192, height = 160, stride = width * 3 + 8, offset = 17;
            byte[] reference = new byte[offset + height * stride];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                byte value = (byte) (60 + ((x / 12 * 17 + y / 11 * 29) % 140));
                Arrays.fill(reference, offset + y * stride + x * 3, offset + y * stride + x * 3 + 3, value);
            }
            Path ref = Files.createTempFile(Path.of("/dev/shm"), "iml_cpp_test_", "");
            buffers.add(ref);
            Files.write(ref, reference);
            byte[] defect = reference.clone();
            for (int y = 35; y < 125; y++) Arrays.fill(defect, offset + y * stride + 45 * 3,
                    offset + y * stride + 145 * 3, (byte) 0);
            Path bad = Files.createTempFile(Path.of("/dev/shm"), "iml_cpp_test_", "");
            buffers.add(bad);
            Files.write(bad, defect);
            Path heatmap = Files.createTempFile(Path.of("/dev/shm"), "iml_cpp_test_", "");
            buffers.add(heatmap);
            String product = "linux-smoke-" + UUID.randomUUID();
            List<Map<String, Object>> jobs = new ArrayList<>();
            for (int camera = 0; camera < 10; camera++) {
                Map<String, Object> job = new LinkedHashMap<>();
                job.put("op", "inspect_shm"); job.put("camera_id", camera); job.put("product_type", product);
                job.put("analysis_profile", "bench-linux-" + camera);
                job.put("shm_name", ref.toString()); job.put("width", width); job.put("height", height);
                job.put("stride", stride); job.put("shm_offset", offset);
                job.put("reference_shm_name", ref.toString()); job.put("reference_width", width);
                job.put("reference_height", height); job.put("reference_stride", stride); job.put("reference_shm_offset", offset);
                job.put("roi_polygon_norm", List.of(Map.of("x", 0.05, "y", 0.05), Map.of("x", 0.95, "y", 0.05),
                        Map.of("x", 0.95, "y", 0.95), Map.of("x", 0.05, "y", 0.95)));
                jobs.add(job);
            }
            var futures = jobs.stream().map(job -> executor.submit(() -> client.commandNoRetry(job))).toList();
            for (var future : futures) {
                var response = future.get(15, TimeUnit.SECONDS);
                assertEquals(BinaryProtocol.MSG_RESPONSE, response.type(), response.header().toString());
                assertEquals(true, response.header().get("ok"), response.header().toString());
            }
            // Profile names and camera-scoped product keys must work with Ubuntu's httplib routing.
            assertEquals(200, request(base, "GET", "/roi-polygon/" + product + "%23cam%3D0", null).statusCode());
            assertEquals(200, request(settingsBase, "PUT", "/api/orchestrator/detector-settings/camera/0", "{\"threshold\":0.1}").statusCode());
            var settings = JSON.readValue(request(settingsBase, "GET", "/api/orchestrator/detector-settings/camera/0", null).body(),
                    new TypeReference<Map<String, Object>>() {});
            assertEquals(0.1, ((Number) ((Map<?, ?>) settings.get("settings")).get("threshold")).doubleValue());
            assertEquals(404, request(settingsBase, "GET", "/api/orchestrator/detector-settings/camera/99", null).statusCode());
            assertEquals(200, request(settingsBase, "GET", "/api/orchestrator/detector-settings/defaults", null).statusCode());
            Map<String, Object> badJob = new LinkedHashMap<>(jobs.get(0));
            badJob.put("shm_name", bad.toString());
            badJob.put("heatmap_u8_output_path", heatmap.toString()); badJob.put("heatmap_max_width", 96);
            var rejection = client.commandNoRetry(badJob);
            assertEquals(BinaryProtocol.MSG_RESPONSE, rejection.type(), rejection.header().toString());
            assertEquals(false, rejection.header().get("ok"), rejection.header().toString());
            assertTrue(Files.size(heatmap) > 0);
            // Simulate service losing its state while Java still caches the reference signature.
            assertEquals(200, request(base, "POST", "/clear-inspection-context", "{}").statusCode());
            var recovered = client.commandNoRetry(jobs.get(0));
            assertEquals(BinaryProtocol.MSG_RESPONSE, recovered.type(), recovered.header().toString());
            assertEquals(true, recovered.header().get("ok"));
            assertEquals(200, request(settingsBase, "DELETE", "/api/orchestrator/detector-settings/camera/0", null).statusCode());
        } finally {
            proxy.stop(0);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); }
            for (Path buffer : buffers) Files.deleteIfExists(buffer);
        }
    }

    private String readLog() {
        try { return Files.readString(temporary.resolve("server.log")); }
        catch (Exception error) { return error.toString(); }
    }

    private static HttpResponse<String> request(String base, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> multipart(String base, String path, byte[] png) throws Exception {
        String boundary = "iml-test-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"product_type\"\r\n\r\nmultipart-smoke\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(png);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
