package com.example.iml.orchestrator.integration.bootstrap.context.state;

import com.example.iml.orchestrator.integration.preview.LivePreviewGate;
import com.example.iml.orchestrator.integration.preview.LivePreviewPublisher;

/** Live preview publisher and gate. */
public final class CameraPreviewState {

    private LivePreviewPublisher livePreview;
    private final LivePreviewGate livePreviewGate = new LivePreviewGate();

    public LivePreviewPublisher livePreview() {
        return livePreview;
    }

    public void setLivePreview(LivePreviewPublisher livePreview) {
        this.livePreview = livePreview;
    }

    public LivePreviewGate livePreviewGate() {
        return livePreviewGate;
    }
}
