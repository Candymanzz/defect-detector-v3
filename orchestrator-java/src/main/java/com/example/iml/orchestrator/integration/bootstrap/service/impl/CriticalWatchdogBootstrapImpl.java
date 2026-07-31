package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.CriticalWatchdogBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.context.port.CriticalWatchdogHost;
import com.example.iml.orchestrator.integration.health.CriticalServiceWatchdog;
import org.apache.logging.log4j.Logger;

/**
 * Только CriticalServiceWatchdog.
 */
public final class CriticalWatchdogBootstrapImpl extends AbstractBootstrapService
        implements CriticalWatchdogBootstrap {

    public CriticalWatchdogBootstrapImpl(Logger log) {
        super(log);
    }

    @Override
    public void start(CriticalWatchdogHost session) {
        CriticalServiceWatchdog watchdog = CriticalServiceWatchdog.start(
                log,
                session, // ProcessRestartHost = config + collaborators + sink
                session.serviceHealthGate()
        );
        session.setCriticalServiceWatchdog(watchdog);
    }
}
