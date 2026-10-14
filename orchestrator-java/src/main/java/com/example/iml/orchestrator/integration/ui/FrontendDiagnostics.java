package com.example.iml.orchestrator.integration.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;

/** Bounded metadata batches for browser mode; Electron writes directly via IPC. */
final class FrontendDiagnostics {
    private static final ObjectMapper JSON = new ObjectMapper();

    static void register(HttpServer server) {
        server.createContext("/diagnostics/frontend", exchange -> {
            try {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
                if (exchange.getRequestMethod().equals("OPTIONS")) {
                    exchange.sendResponseHeaders(204, -1); return;
                }
                if (!exchange.getRequestMethod().equals("POST")) {
                    exchange.sendResponseHeaders(405, -1); return;
                }
                byte[] bytes = exchange.getRequestBody().readNBytes(16385);
                if (bytes.length > 16384) { exchange.sendResponseHeaders(413, -1); return; }
                JsonNode batch;
                try { batch = JSON.readTree(bytes); }
                catch (java.io.IOException e) { exchange.sendResponseHeaders(400, -1); return; }
                if (batch == null || !batch.isArray() || batch.size() > 20) {
                    exchange.sendResponseHeaders(400, -1); return;
                }
                for (JsonNode entry : batch) {
                    String channel = entry.path("channel").asText();
                    if (!java.util.Set.of("requests", "responses", "decisions", "errors").contains(channel)) continue;
                    var safe = JSON.createObjectNode();
                    safe.put("recorded_at", limited(entry.path("recorded_at").asText(), 40));
                    safe.put("event", limited(entry.path("event").asText(), 100));
                    JsonNode details = entry.path("details");
                    for (String key : java.util.List.of("requestId", "method", "path", "status", "duration_ms",
                            "inspection_id", "frame_id", "camera_id", "overall_pass", "action", "python_status",
                            "geometry_status", "anomaly_score", "type", "message_id", "state", "reconnectAttempt", "message", "file", "line")) {
                        JsonNode value = details.path(key);
                        if (value.isNumber() || value.isBoolean()) safe.set(key, value);
                        else if (value.isTextual()) safe.put(key, limited(value.asText(), 1000));
                    }
                    LogManager.getLogger("session.frontend." + channel).info(safe.toString());
                }
                exchange.sendResponseHeaders(204, -1);
            } finally { exchange.close(); }
        });
    }

    private static String limited(String value, int limit) {
        return value.substring(0, Math.min(limit, value.length()));
    }
}
