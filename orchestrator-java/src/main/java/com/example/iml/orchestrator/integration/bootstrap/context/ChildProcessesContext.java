package com.example.iml.orchestrator.integration.bootstrap.context;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.integration.clientws.ClientWsServiceHolder;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.pipeline.stages.WorkerCaptureCoordinator;
import com.example.iml.orchestrator.integration.plc.PlcFinsServiceHolder;
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;
import com.example.iml.orchestrator.integration.ui.UiArtifactsSidecar;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Результат старта child-процессов и ранних collaborator'ов (capture/API/gate).
 * Mutable: watchdog может перезапускать OS-процессы.
 */
public final class ChildProcessesContext {

    private final PreflightContext preflight;

    private List<BinaryRpcSupervisor> pythonPool = List.of();
    private List<ServiceProcessSupervisor> geometryPool = List.of();
    private List<ServiceProcessSupervisor> positioningPool = List.of();
    private List<ExternalServiceProcess> analisSurfaceProcesses = List.of();
    private ExternalServiceProcess lightServerProcess;
    private ExternalServiceProcess ioInputMonitorProcess;
    private ExternalServiceProcess frontendProcess;

    private GeometrySnapshotCache geometrySnapshotCache;
    private GeometryRuntimeConfig geometryRuntimeConfig;
    private PerCameraInspectionGate inspectionGate;
    private ManualLineDirectionService manualLineDirection;
    private PlcFinsServiceHolder plcFinsHolder;
    private ClientWsServiceHolder clientWsHolder;
    private ClientApiMount clientApiMount;
    private WorkerCaptureCoordinator captureCoordinator;
    private UiArtifactsSidecar uiSidecar;

    private Map<String, Object> pythonCfg;
    private Map<String, Object> geometryCfg;
    private Map<String, Object> positioningCfg;
    private Map<String, Object> uiCfg;

    public ChildProcessesContext(PreflightContext preflight) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
    }

    public PreflightContext preflight() {
        return preflight;
    }

    public BootstrapEnvironment env() {
        return preflight.env();
    }

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

    public GeometrySnapshotCache geometrySnapshotCache() {
        return geometrySnapshotCache;
    }

    public void setGeometrySnapshotCache(GeometrySnapshotCache geometrySnapshotCache) {
        this.geometrySnapshotCache = geometrySnapshotCache;
    }

    public GeometryRuntimeConfig geometryRuntimeConfig() {
        return geometryRuntimeConfig;
    }

    public void setGeometryRuntimeConfig(GeometryRuntimeConfig geometryRuntimeConfig) {
        this.geometryRuntimeConfig = geometryRuntimeConfig;
    }

    public PerCameraInspectionGate inspectionGate() {
        return inspectionGate;
    }

    public void setInspectionGate(PerCameraInspectionGate inspectionGate) {
        this.inspectionGate = inspectionGate;
    }

    public ManualLineDirectionService manualLineDirection() {
        return manualLineDirection;
    }

    public void setManualLineDirection(ManualLineDirectionService manualLineDirection) {
        this.manualLineDirection = manualLineDirection;
    }

    public PlcFinsServiceHolder plcFinsHolder() {
        return plcFinsHolder;
    }

    public void setPlcFinsHolder(PlcFinsServiceHolder plcFinsHolder) {
        this.plcFinsHolder = plcFinsHolder;
    }

    public ClientWsServiceHolder clientWsHolder() {
        return clientWsHolder;
    }

    public void setClientWsHolder(ClientWsServiceHolder clientWsHolder) {
        this.clientWsHolder = clientWsHolder;
    }

    public ClientApiMount clientApiMount() {
        return clientApiMount;
    }

    public void setClientApiMount(ClientApiMount clientApiMount) {
        this.clientApiMount = clientApiMount;
    }

    public WorkerCaptureCoordinator captureCoordinator() {
        return captureCoordinator;
    }

    public void setCaptureCoordinator(WorkerCaptureCoordinator captureCoordinator) {
        this.captureCoordinator = captureCoordinator;
    }

    public UiArtifactsSidecar uiSidecar() {
        return uiSidecar;
    }

    public void setUiSidecar(UiArtifactsSidecar uiSidecar) {
        this.uiSidecar = uiSidecar;
    }

    public Map<String, Object> pythonCfg() {
        return pythonCfg;
    }

    public void setPythonCfg(Map<String, Object> pythonCfg) {
        this.pythonCfg = pythonCfg;
    }

    public Map<String, Object> geometryCfg() {
        return geometryCfg;
    }

    public void setGeometryCfg(Map<String, Object> geometryCfg) {
        this.geometryCfg = geometryCfg;
    }

    public Map<String, Object> positioningCfg() {
        return positioningCfg;
    }

    public void setPositioningCfg(Map<String, Object> positioningCfg) {
        this.positioningCfg = positioningCfg;
    }

    public Map<String, Object> uiCfg() {
        return uiCfg;
    }

    public void setUiCfg(Map<String, Object> uiCfg) {
        this.uiCfg = uiCfg;
    }
}
