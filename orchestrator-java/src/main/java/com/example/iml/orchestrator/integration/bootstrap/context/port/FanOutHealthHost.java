package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
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

/**
 * Порт fan-out / health gate / stop-signal.
 */
public interface FanOutHealthHost {

    Map<String, Object> root();

    Path projectRoot();

    ClientWebSocketServer clientWsServer();

    PerCameraInspectionGate inspectionGate();

    PlcFinsServiceHolder plcFinsHolder();

    ExternalServiceProcess frontendProcess();

    IntegrationBootConfig bootConfig();

    List<?> geometryPool();

    void setFanOut(FanOutCoordinator fanOut);

    void setServiceHealthGate(ServiceHealthGate healthGate);

    void setStopSignal(OrchestratorStopSignal stopSignal);
}
