package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DeferredHeatmapTransportTest {
    @Test
    void forwardsDeferredOptionsAndRendersOriginalTokenWithoutAnotherInspection() throws Exception {
        var json = new ObjectMapper();
        var requests = new ArrayList<String>();
        var bodies = new ArrayList<Map<String, Object>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().getPath());
            bodies.add(json.readValue(exchange.getRequestBody(), Map.class));
            var body = exchange.getRequestURI().getPath().equals("/inspect-shm")
                    ? Map.of("status", "ГОДЕН", "anomaly_score", 0.1, "threshold", 0.5,
                            "deferred_heatmap_token", "frozen-frame-1")
                    : Map.of("status", "ГОДЕН", "anomaly_score", 0.1,
                            "heatmap_u8", Map.of("path", "/dev/shm/test-heatmap", "width", 8, "height", 6, "stride", 8));
            byte[] response = json.writeValueAsBytes(body);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var transport = new AnalisSurfaceHttpBinaryRpcSupervisor("deferred-test", base, 2000);
            var verdict = transport.commandNoRetry(Map.of("op", "inspect_shm", "product_type", "bucket",
                    "camera_id", 1, "shm_name", "/test-frame", "width", 16, "height", 12,
                    "defer_heatmap", true, "defer_learning_review", true));
            assertEquals(BinaryProtocol.MSG_RESPONSE, verdict.type());
            assertEquals("frozen-frame-1", verdict.header().get("deferred_heatmap_token"));
            assertEquals(base, verdict.header().get("deferred_heatmap_base_url"));
            assertEquals(true, bodies.get(0).get("defer_heatmap"));
            assertEquals(true, bodies.get(0).get("defer_learning_review"));
            assertFalse(bodies.get(0).containsKey("heatmap_u8_output_path"));
            var rendered = transport.commandNoRetry(Map.of("op", "render_heatmap",
                    "deferred_heatmap_token", verdict.header().get("deferred_heatmap_token"),
                    "heatmap_u8_output_path", "/dev/shm/test-heatmap"));
            assertEquals(BinaryProtocol.MSG_RESPONSE, rendered.type());
            assertEquals(java.util.List.of("/inspect-shm", "/render-heatmap"), requests);
            assertEquals("frozen-frame-1", bodies.get(1).get("token"));
            assertEquals("/dev/shm/test-heatmap", rendered.header().get("heatmap_u8_path"));
            assertEquals(verdict.header().get("anomaly_score"), rendered.header().get("anomaly_score"));
        } finally {
            server.stop(0);
        }
    }
}
