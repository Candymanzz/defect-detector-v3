package com.example.iml.orchestrator.integration.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Compact, bounded, asynchronous diagnostics. Never performs file IO on an inspection thread. */
public final class PerformanceTrace implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CONTEXT = List.of("cycle_id", "trigger_sequence", "camera_id", "frame_id", "request_id");
    private static volatile PerformanceTrace active;
    private static final ThreadLocal<Long> SIGNAL_RECEIVED = new ThreadLocal<>();
    private final String session = UUID.randomUUID().toString();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final ArrayBlockingQueue<Map<String, Object>> queue;
    private final ConcurrentHashMap<Long, Long> triggers = new ConcurrentHashMap<>();
    private final Path directory;
    private final long dailyLimit;
    private final Thread writer;
    private volatile boolean running = true;

    public PerformanceTrace(Path directory, int capacity, long dailyLimit) {
        this.directory = directory;
        this.dailyLimit = dailyLimit;
        queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        writer = new Thread(this::writeLoop, "performance-log-writer");
        writer.setDaemon(true);
        writer.start();
    }

    @SuppressWarnings("unchecked")
    public static void initialize(Path projectRoot, Map<String, Object> root) {
        try {
            Map<String, Object> integration = root.get("integration") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            Map<String, Object> cfg = integration.get("performance_log") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            if (Boolean.FALSE.equals(cfg.get("enabled"))) { shutdown(); return; }
            Path dir = projectRoot.resolve(String.valueOf(cfg.getOrDefault("directory", "cppAnalisSurface/logs"))).normalize();
            int capacity = cfg.get("queue_capacity") instanceof Number n ? n.intValue() : 8192;
            PerformanceTrace trace = new PerformanceTrace(dir, capacity, 512L * 1024 * 1024);
            PerformanceTrace previous = active;
            active = trace;
            if (previous != null) previous.close();
            Runtime.getRuntime().addShutdownHook(new Thread(trace::close, "performance-log-shutdown"));
            emit("orchestrator", "trace_started", values("directory", dir.toAbsolutePath().toString(),
                    "queue_capacity", capacity, "output_transport", "FINS", "clock", "System.nanoTime",
                    "trigger_boundary", "software trigger bus; not electrical camera trigger"));
            System.out.println("[performance] asynchronous diagnostics directory=" + dir.toAbsolutePath());
        } catch (RuntimeException error) {
            System.err.println("[performance] initialization failed: " + error.getMessage());
        }
    }

    public static void shutdown() {
        PerformanceTrace previous = active;
        active = null;
        if (previous != null) previous.close();
    }

    public static Map<String, Object> values(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return result;
    }

    public static double elapsedMs(long start) { return (System.nanoTime() - start) / 1_000_000.0; }

    /** Capture the receive boundary before parsing/gating a UDP packet; never alters trigger logic. */
    public static void receivedSignal() { SIGNAL_RECEIVED.set(System.nanoTime()); }
    public static void clearSignal() { SIGNAL_RECEIVED.remove(); }

    public static String requestId() {
        PerformanceTrace trace = active;
        return trace == null ? UUID.randomUUID().toString() : trace.session + "-" + trace.requests.incrementAndGet();
    }

    public static void trigger(long sequence, String source, List<Integer> cameras) {
        PerformanceTrace trace = active;
        if (trace == null || sequence <= 0) return;
        long now = System.nanoTime();
        Long received = SIGNAL_RECEIVED.get();
        long origin = received == null ? now : received;
        if (trace.triggers.size() > 2048) trace.triggers.entrySet().removeIf(e -> now - e.getValue() > TimeUnit.MINUTES.toNanos(2));
        if (trace.triggers.size() > 4096) trace.triggers.keySet().stream().limit(512).forEach(trace.triggers::remove);
        if (trace.triggers.putIfAbsent(sequence, origin) == null) {
            cycle(sequence, -1, -1, "trigger_received", values("source", source, "cameras", cameras,
                    "boundary", received == null ? "software trigger bus (no transport origin)" : "UDP packet received by Java"));
        }
    }

    public static void cycle(long sequence, int camera, long frame, String event, Map<String, ?> details) {
        PerformanceTrace trace = active;
        if (trace == null) return;
        Map<String, Object> fields = new LinkedHashMap<>(details);
        fields.put("trigger_sequence", sequence);
        fields.put("cycle_id", trace.session + ":" + sequence);
        if (camera >= 0) fields.put("camera_id", camera);
        if (frame >= 0) fields.put("frame_id", frame);
        Long origin = trace.triggers.get(sequence);
        if (origin != null) fields.put("trigger_elapsed_ms", elapsedMs(origin));
        emit("orchestrator", event, fields);
    }

    public static void attach(Map<String, Object> header, long sequence, int camera, long frame) {
        PerformanceTrace trace = active;
        header.put("request_id", requestId());
        header.put("trigger_sequence", sequence);
        header.put("camera_id", camera);
        header.put("frame_id", frame);
        if (trace != null) header.put("cycle_id", trace.session + ":" + sequence);
    }

    public static void copyContext(Map<String, ?> from, Map<String, Object> to) {
        if (from == null) return;
        for (String key : CONTEXT) if (from.get(key) != null) to.put(key, from.get(key));
    }

    public static Map<String, Object> context(Map<String, ?> header) {
        Map<String, Object> fields = new LinkedHashMap<>();
        copyContext(header, fields);
        return fields;
    }

    public static void emit(String component, String event, Map<String, ?> details) {
        PerformanceTrace trace = active;
        if (trace == null || !trace.running) return;
        try {
            Map<String, Object> row = new LinkedHashMap<>(details);
            row.put("schema", 1);
            row.put("ts_utc", Instant.now().toString());
            row.put("session_id", trace.session);
            row.put("pid", ProcessHandle.current().pid());
            row.put("component", component);
            row.put("event", event);
            if (!trace.queue.offer(row)) trace.dropped.incrementAndGet();
        } catch (RuntimeException ignored) { trace.dropped.incrementAndGet(); }
    }

    private void writeLoop() {
        BufferedWriter output = null;
        LocalDate day = null;
        long bytes = 0;
        boolean capWarned = false;
        long lastFlush = System.nanoTime();
        try {
            Files.createDirectories(directory);
            while (running || !queue.isEmpty()) {
                Map<String, Object> row = queue.poll(200, TimeUnit.MILLISECONDS);
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                if (!today.equals(day)) {
                    if (output != null) output.close();
                    Path file = directory.resolve("performance-orchestrator-" + today + "-" + ProcessHandle.current().pid() + ".jsonl");
                    bytes = Files.exists(file) ? Files.size(file) : 0;
                    output = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    day = today;
                    capWarned = false;
                }
                if (row != null) {
                    if (bytes >= dailyLimit) {
                        dropped.incrementAndGet();
                        if (!capWarned) {
                            System.err.println("[performance] daily cap reached; records dropped");
                            capWarned = true;
                        }
                    }
                    else {
                        long lost = dropped.getAndSet(0);
                        if (lost != 0) row.put("dropped_records_since_last_write", lost);
                        String line = JSON.writeValueAsString(row);
                        output.write(line); output.newLine();
                        bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
                    }
                }
                if (System.nanoTime() - lastFlush > TimeUnit.MILLISECONDS.toNanos(200) || !running) {
                    output.flush(); lastFlush = System.nanoTime();
                }
            }
        } catch (Exception error) {
            System.err.println("[performance] writer failed: " + error.getMessage());
        } finally {
            running = false;
            if (output != null) try { output.close(); } catch (Exception ignored) { }
            long lost = dropped.get();
            if (lost != 0) System.err.println("[performance] dropped records=" + lost);
        }
    }

    @Override public void close() {
        running = false;
        if (Thread.currentThread() != writer) try { writer.join(2000); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
