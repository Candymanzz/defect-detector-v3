package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.port.LivePreviewHost;

/**
 * Только LivePreviewPublisher.
 */
public interface LivePreviewBootstrap {

    void start(LivePreviewHost session);
}
