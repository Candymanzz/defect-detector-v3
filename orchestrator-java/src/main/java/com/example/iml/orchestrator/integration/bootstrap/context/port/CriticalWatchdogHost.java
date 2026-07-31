package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.health.CriticalServiceWatchdog;
import com.example.iml.orchestrator.integration.health.ServiceHealthGate;

/**
 * Порт старта CriticalServiceWatchdog.
 * Extends composed {@link ProcessRestartHost}; adds health-gate collaborator + watchdog sink.
 */
public interface CriticalWatchdogHost extends ProcessRestartHost {

    ServiceHealthGate serviceHealthGate();

    void setCriticalServiceWatchdog(CriticalServiceWatchdog watchdog);
}
