package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Порт для {@code CriticalServiceWatchdog}: процессы, пулы, рестарт.
 */
public interface ProcessRestartHost {

    Map<String, Object> integration();

    Path projectRoot();

    boolean windows();

    IntegrationBootConfig bootConfig();

    Map<String, Object> pythonCfg();

    List<BinaryRpcSupervisor> pythonPool();

    List<? extends BinaryRpcSupervisor> geometryPool();

    List<? extends BinaryRpcSupervisor> positioningPool();

    Map<Integer, WorkerProcessSupervisor> workersByCamera();

    ExternalServiceProcess ioInputMonitorProcess();

    void setIoInputMonitorProcess(ExternalServiceProcess process);

    ExternalServiceProcess lightServerProcess();

    void setLightServerProcess(ExternalServiceProcess process);

    List<ExternalServiceProcess> analisSurfaceProcesses();

    void setAnalisSurfaceProcesses(List<ExternalServiceProcess> processes);
}
