package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.FanOutHealthBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.context.port.FanOutHealthCollaboratorView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.FanOutHealthConfigView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.FanOutHealthHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.FanOutHealthSink;
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
        wire(session, session, session);
    }

    void wire(
            FanOutHealthConfigView config,
            FanOutHealthCollaboratorView collaborators,
            FanOutHealthSink sink
    ) {
        FanOutCoordinator fanOut = FanOutCoordinator.fromConfig(
                config.root(),
                config.projectRoot(),
                collaborators.clientWsServer(),
                collaborators.inspectionGate()
        );
        sink.setFanOut(fanOut);
        collaborators.plcFinsHolder().set(fanOut);

        ServiceHealthGate healthGate = new ServiceHealthGate();
        sink.setServiceHealthGate(healthGate);
        fanOut.setHealthGate(healthGate);

        OrchestratorStopSignal stopSignal = new OrchestratorStopSignal();
        sink.setStopSignal(stopSignal);
        if (collaborators.frontendProcess() != null) {
            collaborators.frontendProcess().onUnexpectedExit(() -> {
                log.warn("frontend process exited — requesting orchestrator shutdown (vision_ready=0)");
                stopSignal.request("frontend_exited");
            });
        }

        if (collaborators.clientWsServer() != null) {
            collaborators.clientWsServer().setSessionStateListener(fanOut::onSessionState);
            fanOut.onSessionState(collaborators.clientWsServer().sessionState());
        }
        log.info(
                "integration parallel settings: camera_parallelism={} geometry_pool_size={}",
                config.bootConfig().cameraParallelism(),
                collaborators.geometryPool().size()
        );
    }
}
