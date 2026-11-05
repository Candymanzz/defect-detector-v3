package com.example.iml.orchestrator.integration.camera;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;

/** Delivery from the native dispatcher; listeners must not wait for camera or light SDK calls. */
public final class NativeCameraEvents {
    public static final int EXPOSURE_START = 1;
    public static final int EXPOSURE_END = 2;
    public record Event(int cameraId, int kind, long deviceTicks, long hostMonotonicNs, long dropped) { }
    private static final CopyOnWriteArrayList<Consumer<Event>> LISTENERS = new CopyOnWriteArrayList<>();

    private NativeCameraEvents() { }

    public static AutoCloseable subscribe(Consumer<Event> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    // JNI uses this exact signature. Host clock is Linux CLOCK_MONOTONIC, as is System.nanoTime().
    static void onNativeEvent(int cameraId, int kind, long ticks, long mono, long dropped) {
        Event event = new Event(cameraId, kind, ticks, mono, dropped);
        for (Consumer<Event> listener : LISTENERS) {
            try { listener.accept(event); }
            catch (RuntimeException error) { LogManager.getLogger(NativeCameraEvents.class).error("Camera event listener failed", error); }
        }
    }
}
