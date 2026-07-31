package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.plc.PlcFinsServiceHolder;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;

import java.util.List;

/** Existing collaborators needed while wiring fan-out / health. */
public interface FanOutHealthCollaboratorView {

    ClientWebSocketServer clientWsServer();

    PerCameraInspectionGate inspectionGate();

    PlcFinsServiceHolder plcFinsHolder();

    ExternalServiceProcess frontendProcess();

    List<?> geometryPool();
}
