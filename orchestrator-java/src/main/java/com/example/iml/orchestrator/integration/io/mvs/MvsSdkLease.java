package com.example.iml.orchestrator.integration.io.mvs;

import java.util.HashMap;
import java.util.Map;

/** Process-wide MvCameraControl lifetime shared by IO and lights. */
public final class MvsSdkLease implements AutoCloseable {
    public static final Object ENUMERATION_LOCK = new Object();
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
