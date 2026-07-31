package com.example.iml.orchestrator.integration.bootstrap.context.port;

/**
 * Порт LivePreviewPublisher.
 * Composed of narrower views for ISP: config / collaborators / sink.
 */
public interface LivePreviewHost
        extends LivePreviewConfigView, LivePreviewCollaboratorView, LivePreviewSink {
}
