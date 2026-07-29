package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.port.CriticalWatchdogHost;

/**
 * Старт {@code CriticalServiceWatchdog} (после workers/pools).
 */
public interface CriticalWatchdogBootstrap {

    void start(CriticalWatchdogHost session);
}
