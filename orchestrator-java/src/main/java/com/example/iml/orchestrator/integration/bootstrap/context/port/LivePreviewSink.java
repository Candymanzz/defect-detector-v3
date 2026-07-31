package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.preview.LivePreviewPublisher;

/** Sink for objects created during live-preview bootstrap. */
public interface LivePreviewSink {

    void setLivePreview(LivePreviewPublisher livePreview);
}
