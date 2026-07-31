package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;

import java.util.List;
import java.util.Map;

/** Existing pools / workers needed while restarting critical processes. */
public interface ProcessRestartCollaboratorView {

    List<BinaryRpcSupervisor> pythonPool();

    List<? extends BinaryRpcSupervisor> geometryPool();

    List<? extends BinaryRpcSupervisor> positioningPool();

    Map<Integer, WorkerProcessSupervisor> workersByCamera();
}
