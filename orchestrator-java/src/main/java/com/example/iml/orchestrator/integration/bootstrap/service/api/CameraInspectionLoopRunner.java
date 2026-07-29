package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraInspectionLoopHost;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;

/**
 * Запуск и ожидание per-camera inspection loops.
 */
public interface CameraInspectionLoopRunner {

    void runBlocking(
            CameraInspectionLoopHost session,
            TriggerRuntimeBootstrap.TriggerWireResult triggerWire,
            OrchestratorStopSignal stopSignal
    ) throws Exception;
}
