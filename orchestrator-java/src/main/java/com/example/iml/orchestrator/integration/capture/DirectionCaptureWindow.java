package com.example.iml.orchestrator.integration.capture;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Shared cancellation token read by camera workers while waiting for hardware frames. */
public final class DirectionCaptureWindow {
    private static Path path;
    private static long generation, active;
    private static final Map<Long, Long> sequences = new ConcurrentHashMap<>();
    public static synchronized void enable() {
        if (path != null) return;
        try { path = Files.createTempFile("iml-capture-window-", ".token"); path.toFile().deleteOnExit(); write(0); }
        catch (IOException e) { throw new IllegalStateException("Cannot create capture window", e); }
    }
    public static synchronized boolean enabled() { return path != null; }
    public static synchronized void open() { if (path != null) { active = ++generation; write(active); } }
    public static synchronized void close() { if (path != null) { active = 0; write(0); } }
    public static synchronized void disable() {
        if (path == null) return;
        close();
        try { Files.deleteIfExists(path); } catch (IOException e) { throw new IllegalStateException(e); }
        path = null; sequences.clear();
    }
    private static void write(long token) {
        try { Files.writeString(path, Long.toString(token)); }
        catch (IOException e) { throw new IllegalStateException("Cannot update capture window", e); }
    }
    public static synchronized void bind(long sequence) {
        if (path != null) { sequences.put(sequence, active); sequences.remove(sequence - 1024); }
    }
    public static synchronized long token(long sequence) { return sequences.getOrDefault(sequence, 0L); }
    public static synchronized Map<String, Object> command(long token) {
        if (path == null) return Map.of("op", "capture", "wait_frame", true);
        return Map.of("op", "capture", "wait_frame", true, "capture_window_path", path.toString(),
                "capture_window_token", Long.toString(token));
    }
    private DirectionCaptureWindow() { }
}
