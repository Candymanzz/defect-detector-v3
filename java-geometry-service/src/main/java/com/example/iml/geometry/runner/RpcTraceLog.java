package com.example.iml.geometry.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSONL request/response trace for one stdio worker. Binary image payloads are represented by their size. */
final class RpcTraceLog {
    private static final Logger log = LogManager.getLogger(RpcTraceLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Call(String id, long startedNanos) {
    }

    private final Path file;
    private long sequence;
    private boolean writable = true;

    RpcTraceLog() {
        this(resolveFile());
    }

    RpcTraceLog(Path file) {
        this.file = file;
    }

    private static Path resolveFile() {
        String session = System.getenv("IML_TRACE_SESSION_DIR");
        Path target = null;
        if (session != null && !session.isBlank()) {
            try {
                target = Path.of(session, "java-geometry", "rpc-" + ProcessHandle.current().pid() + ".jsonl");
            } catch (RuntimeException e) {
                log.warn("Invalid geometry RPC trace directory: {}", e.getMessage());
            }
        }
        return target;
    }

    Call request(int type, Map<String, Object> header, int payloadBytes) {
        long started = System.nanoTime();
        Call call = new Call(ProcessHandle.current().pid() + "-" + (++sequence), started);
        Map<String, Object> row = base("request", call);
        row.put("message_type", type);
        row.put("op", header.get("op"));
        row.put("camera_id", header.get("camera_id"));
        row.put("frame_id", header.get("frame_id"));
        row.put("header", safeHeader(header));
        row.put("payload_bytes", payloadBytes);
        append(row);
        return call;
    }

    void response(Call call, int type, Map<String, Object> header, int payloadBytes) {
        Map<String, Object> row = base("response", call);
        row.put("message_type", type);
        row.put("status", header.getOrDefault("status", type == 3 ? "ERROR" : "OK"));
        row.put("header", safeHeader(header));
        row.put("payload_bytes", payloadBytes);
        row.put("duration_ms", (System.nanoTime() - call.startedNanos()) / 1_000_000.0);
        append(row);
    }

    void failure(Call call, Exception error) {
        Map<String, Object> row = base("failure", call);
        row.put("error_class", error.getClass().getName());
        row.put("error", error.getMessage());
        row.put("duration_ms", (System.nanoTime() - call.startedNanos()) / 1_000_000.0);
        append(row);
    }

    void protocolError(Exception error) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("event", "protocol_error");
        row.put("error_class", error.getClass().getName());
        row.put("error", error.getMessage());
        append(row);
    }

    private static Map<String, Object> base(String event, Call call) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timestamp", OffsetDateTime.now().toString());
        row.put("event", event);
        row.put("request_id", call.id());
        return row;
    }

    private static Map<String, Object> safeHeader(Map<String, Object> header) {
        Map<String, Object> safe = new LinkedHashMap<>();
        header.forEach((key, value) -> safe.put(key,
                value instanceof String text && text.length() > 8192
                        ? "[large value omitted, " + text.length() + " characters]" : value));
        return safe;
    }

    private synchronized void append(Map<String, Object> row) {
        if (file == null || !writable) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            writable = false;
            log.warn("Geometry RPC trace disabled after write failure: {}", e.getMessage());
        }
    }
}
