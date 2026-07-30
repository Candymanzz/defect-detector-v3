package com.example.iml.orchestrator.integration.bootstrap.context.impl;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.port.AbstractCameraRuntimeHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.FanOutHealthHost;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.health.ServiceHealthGate;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.plc.PlcFinsServiceHolder;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Адаптер: fan-out / health / stop-signal. */
public final class FanOutHealthHostImpl extends AbstractCameraRuntimeHost implements FanOutHealthHost {

    public FanOutHealthHostImpl(CameraRuntimeContext runtime) {
        super(runtime);
    }

    @Override
    public Map<String, Object> root() {
        return rootMap();
    }

    @Override
    public Path projectRoot() {
        return projectRootPath();
    }

    @Override
    public ClientWebSocketServer clientWsServer() {
        return ui().clientWsServer();
    }

    @Override
    public PerCameraInspectionGate inspectionGate() {
        return processes().inspectionGate();
    }

    @Override
    public PlcFinsServiceHolder plcFinsHolder() {
        return processes().plcFinsHolder();
    }

    @Override
    public ExternalServiceProcess frontendProcess() {
        return processes().frontendProcess();
    }

    @Override
    public IntegrationBootConfig bootConfig() {
        return bootCfg();
    }

    @Override
    public List<?> geometryPool() {
        return processes().geometryPool();
    }

    @Override
    public void setFanOut(FanOutCoordinator fanOut) {
        health().setFanOut(fanOut);
    }

    @Override
    public void setServiceHealthGate(ServiceHealthGate healthGate) {
        health().setServiceHealthGate(healthGate);
    }

    @Override
    public void setStopSignal(OrchestratorStopSignal stopSignal) {
        health().setStopSignal(stopSignal);
    }
}
