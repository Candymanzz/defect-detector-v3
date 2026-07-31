package com.example.iml.orchestrator.integration.ui.artifacts;

/**
 * Queued UI publish work with cleanup when superseded or rejected.
 */
public final class UiPublishTask implements Runnable {

    private final int cameraId;
    private final Runnable delegate;
    private final Runnable cleanup;

    public UiPublishTask(int cameraId, Runnable delegate, Runnable cleanup) {
        this.cameraId = cameraId;
        this.delegate = delegate;
        this.cleanup = cleanup;
    }

    public int cameraId() {
        return cameraId;
    }

    @Override
    public void run() {
        delegate.run();
    }

    public void discard() {
        cleanup.run();
    }
}
