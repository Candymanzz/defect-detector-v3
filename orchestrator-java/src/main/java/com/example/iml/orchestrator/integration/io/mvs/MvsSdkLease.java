package com.example.iml.orchestrator.integration.io.mvs;

import java.util.HashMap;
import java.util.Map;

/** Process-wide MvCameraControl lifetime shared by JNI cameras, IO and lights. */
public final class MvsSdkLease implements AutoCloseable {
    public static final Object ENUMERATION_LOCK = new Object();
    private static final java.util.Set<String> serialPorts = new java.util.LinkedHashSet<>();
    /** Register all configured ports before the first serial enumeration (SDK caches its list). */
    public static void registerSerialPorts(java.util.Collection<String> ports) {
        synchronized (ENUMERATION_LOCK) {
            for (String port : ports) if (port != null && !port.isBlank()) serialPorts.add(port);
        }
    }
    public static com.sun.jna.Memory serialPortList(String requested) {
        synchronized (ENUMERATION_LOCK) {
            registerSerialPorts(java.util.List.of(requested));
            if (serialPorts.size() > 64) throw new IllegalArgumentException("MVS supports at most 64 serial ports");
            com.sun.jna.Memory result = new com.sun.jna.Memory(5140);
            result.clear(); result.setInt(0, serialPorts.size());
            int index = 0;
            for (String port : serialPorts) {
                byte[] bytes = port.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (bytes.length >= 64) { result.close(); throw new IllegalArgumentException("MVS serial port exceeds 63 bytes"); }
                result.write(4L + 80L * index++, bytes, 0, bytes.length);
            }
            return result;
        }
    }
    private static final Map<String, State> states = new HashMap<>();
    private static final class State {
        int users;
        final Runnable finalizeSdk;
        State(Runnable finalizeSdk) { this.finalizeSdk = finalizeSdk; }
    }
    private final String key;
    private boolean closed;
    private MvsSdkLease(String key) { this.key = key; }
    public static synchronized MvsSdkLease acquire(String key, Runnable initialize, Runnable finalizeSdk) {
        State state = states.get(key);
        if (state == null) {
            initialize.run(); state = new State(finalizeSdk); states.put(key, state);
        }
        state.users++;
        return new MvsSdkLease(key);
    }
    @Override public void close() {
        synchronized (MvsSdkLease.class) {
            if (closed) return;
            closed = true;
            State state = states.get(key);
            if (--state.users == 0) {
                states.remove(key); state.finalizeSdk.run();
            }
        }
    }
}
