package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.pipeline.stages.WorkerCaptureCoordinator;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;

import java.util.Map;

/** Existing collaborators needed while wiring triggers. */
public interface TriggerCollaboratorView {

    Map<Integer, WorkerProcessSupervisor> workersByCamera();

    PerCameraInspectionGate inspectionGate();

    WorkerCaptureCoordinator captureCoordinator();

    ManualLineDirectionService manualLineDirection();

    LightTriggerClient lightClient();

    FanOutCoordinator fanOut();

    OrchestratorStopSignal stopSignal();
}
