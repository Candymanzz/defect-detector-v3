package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.health.ServiceHealthGate;

/** Sink for objects created during fan-out / health wiring. */
public interface FanOutHealthSink {

    void setFanOut(FanOutCoordinator fanOut);

    void setServiceHealthGate(ServiceHealthGate healthGate);

    void setStopSignal(OrchestratorStopSignal stopSignal);
}
