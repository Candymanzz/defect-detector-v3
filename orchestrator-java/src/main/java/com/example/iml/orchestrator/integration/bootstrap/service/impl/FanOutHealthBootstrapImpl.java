package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.FanOutHealthBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.context.port.FanOutHealthHost;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.health.ServiceHealthGate;
import org.apache.logging.log4j.Logger;

/**
 * Fan-out, health gate, stop-signal, session→PLC (без watchdog — он после workers).
 */
public final class FanOutHealthBootstrapImpl extends AbstractBootstrapService implements FanOutHealthBootstrap {

    public FanOutHealthBootstrapImpl(Logger log) {
        super(log);
    }

    @Override
    public void wire(FanOutHealthHost session) {
        FanOutCoordinator fanOut = FanOutCoordinator.fromConfig(
                session.root(),
                session.projectRoot(),
                session.clientWsServer(),
                session.inspectionGate()
        );
        session.setFanOut(fanOut);
        session.plcFinsHolder().set(fanOut);

        ServiceHealthGate healthGate = new ServiceHealthGate();
        session.setServiceHealthGate(healthGate);
        fanOut.setHealthGate(healthGate);

        OrchestratorStopSignal stopSignal = new OrchestratorStopSignal();
        session.setStopSignal(stopSignal);
        if (session.frontendProcess() != null) {
            session.frontendProcess().onUnexpectedExit(() -> {
                log.warn("frontend process exited — requesting orchestrator shutdown (vision_ready=0)");
                stopSignal.request("frontend_exited");
            });
        }

        if (session.clientWsServer() != null) {
            session.clientWsServer().setSessionStateListener(fanOut::onSessionState);
            fanOut.onSessionState(session.clientWsServer().sessionState());
        }
        log.info(
                "integration parallel settings: camera_parallelism={} geometry_pool_size={}",
                session.bootConfig().cameraParallelism(),
                session.geometryPool().size()
        );
    }
}
