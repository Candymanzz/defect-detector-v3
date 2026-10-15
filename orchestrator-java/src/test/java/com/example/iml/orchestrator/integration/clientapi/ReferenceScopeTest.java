package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceScopeTest {
    @Test
    void uploadsKeepCameraReferencesSeparateAndDoNotDuplicateScope() throws Exception {
        // Check the real HTTP contract, rather than the nonexistent phase API.
        var received = new ArrayList<Map<String, Object>>();
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload-ref-shm", exchange -> {
            received.add(json.readValue(exchange.getRequestBody(), Map.class));
            byte[] response = "{\"status\":\"ok\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var supervisor = new AnalisSurfaceHttpBinaryRpcSupervisor("scope-test",
                    "http://127.0.0.1:" + server.getAddress().getPort(), 2000);
            var original = Map.<String, Object>of("op", "set_reference_shm", "product_type", "bucket",
                    "camera_id", 3, "shm_name", "/scope-test-cam3", "width", 16, "height", 12);
            assertEquals(BinaryProtocol.MSG_RESPONSE, supervisor.commandNoRetry(original).type());
            for (var view : List.of(
                    Map.<String, Object>of("camera_id", 4, "shm_name", "/scope-test-cam4", "width", 16, "height", 12),
                    Map.<String, Object>of("camera_id", 3, "shm_name", "/scope-test-cam3", "width", 16, "height", 12))) {
                String product = ((Number) view.get("camera_id")).intValue() == 3 ? "bucket#cam=3" : "bucket";
                assertEquals(BinaryProtocol.MSG_RESPONSE, supervisor.commandNoRetry(Map.of(
                        "op", "sync_client_reference_bundle", "product_type", product,
                        "views", List.of(view))).type());
            }
            assertEquals(List.of("bucket#cam=3", "bucket#cam=4", "bucket#cam=3"),
                    received.stream().map(body -> body.get("product_type")).toList());
            assertEquals("bucket", original.get("product_type"));
        } finally {
            server.stop(0);
        }
    }
}
