package com.example.iml.orchestrator.integration.preview;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime gate for pausing/resuming live preview capture/publish.
 */
public final class LivePreviewGate {
    private final AtomicBoolean paused = new AtomicBoolean(false);

    public boolean isPaused() {
        return paused.get();
    }

    public void setPaused(boolean value) {
        paused.set(value);
    }
}
