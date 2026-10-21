package com.example.iml.orchestrator.integration.logging;

import com.example.iml.orchestrator.integration.clientapi.CppAnalisSurfaceHttpSupervisor;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PerformanceHttpCorrelationTest {
    @Test void sendsCycleCameraFrameAndMatchingRequestIdToCppWithoutChangingVerdict() throws Exception {
        AtomicReference<String> failure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/inspect-shm", exchange -> {
            try {
                var body = new ObjectMapper().readTree(exchange.getRequestBody());
                assertEquals("cycle:12", body.path("cycle_id").asText());
                assertEquals(12, body.path("trigger_sequence").asInt());
                assertEquals(2, body.path("camera_id").asInt());
                assertEquals(123, body.path("frame_id").asInt());
                assertEquals(body.path("request_id").asText(), exchange.getRequestHeaders().getFirst("X-Request-Id"));
                assertEquals("cycle:12", exchange.getRequestHeaders().getFirst("X-Cycle-Id"));
                exchange.getResponseHeaders().set("X-Request-Id", body.path("request_id").asText());
            } catch (Throwable error) { failure.set(error.toString()); }
            byte[] response = "{\"status\":\"ГОДЕН\",\"anomaly_score\":0,\"py_total_ms\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try (CppAnalisSurfaceHttpSupervisor client = new CppAnalisSurfaceHttpSupervisor("test", "http://127.0.0.1:" + server.getAddress().getPort(), 2000)) {
            BinaryProtocol.Message response = client.command(Map.of("op", "inspect_shm", "product_type", "test",
                    "camera_id", 2, "frame_id", 123, "cycle_id", "cycle:12", "trigger_sequence", 12,
                    "shm_name", "test", "width", 4, "height", 4, "stride", 12));
            assertEquals(BinaryProtocol.MSG_RESPONSE, response.type());
            assertNull(failure.get(), failure.get());
        } finally { server.stop(0); }
    }
}
