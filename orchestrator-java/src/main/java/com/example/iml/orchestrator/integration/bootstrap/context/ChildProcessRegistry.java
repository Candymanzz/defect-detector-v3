package com.example.iml.orchestrator.integration.bootstrap.context;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;

import java.util.List;

/**
 * OS child processes and RPC service pools (mutable: watchdog may replace processes).
 */
public final class ChildProcessRegistry {

    private List<BinaryRpcSupervisor> pythonPool = List.of();
    private List<ServiceProcessSupervisor> geometryPool = List.of();
    private List<ServiceProcessSupervisor> positioningPool = List.of();
    private List<ExternalServiceProcess> analisSurfaceProcesses = List.of();
    private ExternalServiceProcess lightServerProcess;
    private ExternalServiceProcess ioInputMonitorProcess;
    private ExternalServiceProcess frontendProcess;

    public List<BinaryRpcSupervisor> pythonPool() {
        return pythonPool;
    }

    public void setPythonPool(List<BinaryRpcSupervisor> pythonPool) {
        this.pythonPool = pythonPool == null ? List.of() : pythonPool;
    }

    public List<ServiceProcessSupervisor> geometryPool() {
        return geometryPool;
    }

    public void setGeometryPool(List<ServiceProcessSupervisor> geometryPool) {
        this.geometryPool = geometryPool == null ? List.of() : geometryPool;
    }

    public List<ServiceProcessSupervisor> positioningPool() {
        return positioningPool;
    }

    public void setPositioningPool(List<ServiceProcessSupervisor> positioningPool) {
        this.positioningPool = positioningPool == null ? List.of() : positioningPool;
    }

    public List<ExternalServiceProcess> analisSurfaceProcesses() {
        return analisSurfaceProcesses;
    }

    public void setAnalisSurfaceProcesses(List<ExternalServiceProcess> analisSurfaceProcesses) {
        this.analisSurfaceProcesses = analisSurfaceProcesses == null ? List.of() : analisSurfaceProcesses;
    }

    public ExternalServiceProcess lightServerProcess() {
        return lightServerProcess;
    }

    public void setLightServerProcess(ExternalServiceProcess lightServerProcess) {
        this.lightServerProcess = lightServerProcess;
    }

    public ExternalServiceProcess ioInputMonitorProcess() {
        return ioInputMonitorProcess;
    }

    public void setIoInputMonitorProcess(ExternalServiceProcess ioInputMonitorProcess) {
        this.ioInputMonitorProcess = ioInputMonitorProcess;
    }

    public ExternalServiceProcess frontendProcess() {
        return frontendProcess;
    }

    public void setFrontendProcess(ExternalServiceProcess frontendProcess) {
        this.frontendProcess = frontendProcess;
    }
}
