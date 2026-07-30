package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.health.CriticalServiceWatchdog;
import com.example.iml.orchestrator.integration.health.ServiceHealthGate;

/**
 * Порт старта CriticalServiceWatchdog.
 */
public interface CriticalWatchdogHost extends ProcessRestartHost {

    ServiceHealthGate serviceHealthGate();

    void setCriticalServiceWatchdog(CriticalServiceWatchdog watchdog);
}
