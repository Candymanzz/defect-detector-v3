package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.port.FanOutHealthHost;

/**
 * Создание FanOutCoordinator, ServiceHealthGate, stop-signal, session→PLC binding.
 */
public interface FanOutHealthBootstrap {

    void wire(FanOutHealthHost session);
}
