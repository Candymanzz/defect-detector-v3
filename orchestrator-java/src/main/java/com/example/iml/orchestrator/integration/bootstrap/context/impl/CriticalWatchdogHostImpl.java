package com.example.iml.orchestrator.integration.bootstrap.context.impl;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.port.AbstractCameraRuntimeHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CriticalWatchdogHost;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.health.CriticalServiceWatchdog;
import com.example.iml.orchestrator.integration.health.ServiceHealthGate;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Адаптер: process restart + critical watchdog. */
public final class CriticalWatchdogHostImpl extends AbstractCameraRuntimeHost implements CriticalWatchdogHost {

    public CriticalWatchdogHostImpl(CameraRuntimeContext runtime) {
        super(runtime);
    }

    @Override
    public Map<String, Object> integration() {
        return integrationMap();
    }

    @Override
    public Path projectRoot() {
        return projectRootPath();
    }

    @Override
    public boolean windows() {
        return env().windows();
    }

    @Override
    public IntegrationBootConfig bootConfig() {
        return bootCfg();
    }

    @Override
    public Map<String, Object> pythonCfg() {
        return processes().pythonCfg();
    }

    @Override
    public List<BinaryRpcSupervisor> pythonPool() {
        return processes().pythonPool();
    }

    @Override
    public List<? extends BinaryRpcSupervisor> geometryPool() {
        return processes().geometryPool();
    }

    @Override
    public List<? extends BinaryRpcSupervisor> positioningPool() {
        return processes().positioningPool();
    }

    @Override
    public Map<Integer, WorkerProcessSupervisor> workersByCamera() {
        return workers().workersByCamera();
    }

    @Override
    public ExternalServiceProcess ioInputMonitorProcess() {
        return processes().ioInputMonitorProcess();
    }

    @Override
    public void setIoInputMonitorProcess(ExternalServiceProcess process) {
        processes().setIoInputMonitorProcess(process);
    }

    @Override
    public ExternalServiceProcess lightServerProcess() {
        return processes().lightServerProcess();
    }

    @Override
    public void setLightServerProcess(ExternalServiceProcess process) {
        processes().setLightServerProcess(process);
    }

    @Override
    public List<ExternalServiceProcess> analisSurfaceProcesses() {
        return processes().analisSurfaceProcesses();
    }

    @Override
    public void setAnalisSurfaceProcesses(List<ExternalServiceProcess> processes) {
        processes().setAnalisSurfaceProcesses(processes);
    }

    @Override
    public ServiceHealthGate serviceHealthGate() {
        return health().serviceHealthGate();
    }

    @Override
    public void setCriticalServiceWatchdog(CriticalServiceWatchdog watchdog) {
        health().setCriticalServiceWatchdog(watchdog);
    }
}
