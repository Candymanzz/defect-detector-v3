package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.UiRuntimeContext;

/**
 * UI HTTP, client WebSocket, frame archive.
 */
public interface UiRuntimeBootstrap {

    void bootstrap(UiRuntimeContext ui);
}
