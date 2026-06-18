package com.example.iml.orchestrator.integration.preview;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime gate for pausing/resuming live preview capture/publish.
 */
public final class LivePreviewGate {
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean imagesEnabled = new AtomicBoolean(true);

    public boolean isPaused() {
        return paused.get();
    }

    public void setPaused(boolean value) {
        paused.set(value);
    }

    public boolean areImagesEnabled() {
        return imagesEnabled.get();
    }

    public void setImagesEnabled(boolean value) {
        imagesEnabled.set(value);
    }
}
