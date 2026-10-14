package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DeferredHeatmapTransportTest {
    @Test
    void rendersCachedResultAtItsOriginalProcessWithoutAnotherInspection() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> calls = new ArrayList<>();
        ObjectMapper json = new ObjectMapper();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.add(path);
            var body = json.readTree(exchange.getRequestBody());
            String response;
            if (path.equals("/inspect-shm")) {
                assertTrue(body.get("defer_heatmap").asBoolean());
                assertFalse(body.has("heatmap_u8_output_path"));
                response = "{\"status\":\"PASS\",\"anomaly_score\":0.1,\"threshold\":0.5,\"deferred_heatmap_token\":\"frame-token\"}";
            } else {
                assertEquals("/render-heatmap", path);
                assertEquals("frame-token", body.get("token").asText());
                response = "{\"status\":\"PASS\",\"anomaly_score\":0.1,\"heatmap_u8\":{\"path\":\"/dev/shm/test-heatmap\",\"width\":8,\"height\":6,\"stride\":8}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new AnalisSurfaceHttpBinaryRpcSupervisor("detector", baseUrl, 1000);
            var primary = client.commandNoRetry(Map.of(
                    "op", "inspect_shm", "product_type", "bench", "camera_id", 0,
                    "shm_name", "/frame", "width", 8, "height", 6, "defer_heatmap", true));
            assertEquals(BinaryProtocol.MSG_RESPONSE, primary.type());
            assertEquals(baseUrl, primary.header().get("deferred_heatmap_base_url"));
            var rendered = client.commandNoRetry(Map.of("op", "render_heatmap",
                    "deferred_heatmap_token", primary.header().get("deferred_heatmap_token"),
                    "heatmap_u8_output_path", "/dev/shm/test-heatmap"));
            assertEquals(BinaryProtocol.MSG_RESPONSE, rendered.type());
            assertEquals("/dev/shm/test-heatmap", rendered.header().get("heatmap_u8_path"));
            assertEquals(List.of("/inspect-shm", "/render-heatmap"), calls);
        } finally {
            server.stop(0);
        }
    }
}
