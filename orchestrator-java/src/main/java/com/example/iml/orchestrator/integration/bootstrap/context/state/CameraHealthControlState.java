package com.example.iml.orchestrator.integration.bootstrap.context.state;

import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.health.CriticalServiceWatchdog;
import com.example.iml.orchestrator.integration.health.ServiceHealthGate;

/** Fan-out, health gate, stop signal, critical watchdog. */
public final class CameraHealthControlState {

    private FanOutCoordinator fanOut;
    private ServiceHealthGate serviceHealthGate;
    private OrchestratorStopSignal stopSignal;
    private CriticalServiceWatchdog criticalServiceWatchdog;

    public FanOutCoordinator fanOut() {
        return fanOut;
    }

    public void setFanOut(FanOutCoordinator fanOut) {
        this.fanOut = fanOut;
    }

    public ServiceHealthGate serviceHealthGate() {
        return serviceHealthGate;
    }

    public void setServiceHealthGate(ServiceHealthGate serviceHealthGate) {
        this.serviceHealthGate = serviceHealthGate;
    }

    public OrchestratorStopSignal stopSignal() {
        return stopSignal;
    }

    public void setStopSignal(OrchestratorStopSignal stopSignal) {
        this.stopSignal = stopSignal;
    }

    public CriticalServiceWatchdog criticalServiceWatchdog() {
        return criticalServiceWatchdog;
    }

    public void setCriticalServiceWatchdog(CriticalServiceWatchdog criticalServiceWatchdog) {
        this.criticalServiceWatchdog = criticalServiceWatchdog;
    }
}
