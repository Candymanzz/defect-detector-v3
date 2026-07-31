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

/** Facade over process registry / early collaborators / config slices. */
public final class ChildProcessesContext {

    private final PreflightContext preflight;
    private final ChildProcessRegistry processRegistry = new ChildProcessRegistry();
    private final EarlyCollaboratorRegistry collaborators = new EarlyCollaboratorRegistry();
    private final IntegrationConfigSlices configSlices = new IntegrationConfigSlices();

    public ChildProcessesContext(PreflightContext preflight) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
    }

    public PreflightContext preflight() { return preflight; }
    public BootstrapEnvironment env() { return preflight.env(); }
    public ChildProcessRegistry processRegistry() { return processRegistry; }
    public EarlyCollaboratorRegistry collaborators() { return collaborators; }
    public IntegrationConfigSlices configSlices() { return configSlices; }

    public List<BinaryRpcSupervisor> pythonPool() { return processRegistry.pythonPool(); }
    public void setPythonPool(List<BinaryRpcSupervisor> pythonPool) { processRegistry.setPythonPool(pythonPool); }
    public List<ServiceProcessSupervisor> geometryPool() { return processRegistry.geometryPool(); }
    public void setGeometryPool(List<ServiceProcessSupervisor> geometryPool) { processRegistry.setGeometryPool(geometryPool); }
    public List<ServiceProcessSupervisor> positioningPool() { return processRegistry.positioningPool(); }
    public void setPositioningPool(List<ServiceProcessSupervisor> positioningPool) { processRegistry.setPositioningPool(positioningPool); }
    public List<ExternalServiceProcess> analisSurfaceProcesses() { return processRegistry.analisSurfaceProcesses(); }
    public void setAnalisSurfaceProcesses(List<ExternalServiceProcess> analisSurfaceProcesses) {
        processRegistry.setAnalisSurfaceProcesses(analisSurfaceProcesses);
    }
    public ExternalServiceProcess lightServerProcess() { return processRegistry.lightServerProcess(); }
    public void setLightServerProcess(ExternalServiceProcess lightServerProcess) {
        processRegistry.setLightServerProcess(lightServerProcess);
    }
    public ExternalServiceProcess ioInputMonitorProcess() { return processRegistry.ioInputMonitorProcess(); }
    public void setIoInputMonitorProcess(ExternalServiceProcess ioInputMonitorProcess) {
        processRegistry.setIoInputMonitorProcess(ioInputMonitorProcess);
    }
    public ExternalServiceProcess frontendProcess() { return processRegistry.frontendProcess(); }
    public void setFrontendProcess(ExternalServiceProcess frontendProcess) {
        processRegistry.setFrontendProcess(frontendProcess);
    }

    public GeometrySnapshotCache geometrySnapshotCache() { return collaborators.geometrySnapshotCache(); }
    public void setGeometrySnapshotCache(GeometrySnapshotCache geometrySnapshotCache) {
        collaborators.setGeometrySnapshotCache(geometrySnapshotCache);
    }
    public GeometryRuntimeConfig geometryRuntimeConfig() { return collaborators.geometryRuntimeConfig(); }
    public void setGeometryRuntimeConfig(GeometryRuntimeConfig geometryRuntimeConfig) {
        collaborators.setGeometryRuntimeConfig(geometryRuntimeConfig);
    }
    public PerCameraInspectionGate inspectionGate() { return collaborators.inspectionGate(); }
    public void setInspectionGate(PerCameraInspectionGate inspectionGate) {
        collaborators.setInspectionGate(inspectionGate);
    }
    public ManualLineDirectionService manualLineDirection() { return collaborators.manualLineDirection(); }
    public void setManualLineDirection(ManualLineDirectionService manualLineDirection) {
        collaborators.setManualLineDirection(manualLineDirection);
    }
    public PlcFinsServiceHolder plcFinsHolder() { return collaborators.plcFinsHolder(); }
    public void setPlcFinsHolder(PlcFinsServiceHolder plcFinsHolder) { collaborators.setPlcFinsHolder(plcFinsHolder); }
    public ClientWsServiceHolder clientWsHolder() { return collaborators.clientWsHolder(); }
    public void setClientWsHolder(ClientWsServiceHolder clientWsHolder) { collaborators.setClientWsHolder(clientWsHolder); }
    public ClientApiMount clientApiMount() { return collaborators.clientApiMount(); }
    public void setClientApiMount(ClientApiMount clientApiMount) { collaborators.setClientApiMount(clientApiMount); }
    public WorkerCaptureCoordinator captureCoordinator() { return collaborators.captureCoordinator(); }
    public void setCaptureCoordinator(WorkerCaptureCoordinator captureCoordinator) {
        collaborators.setCaptureCoordinator(captureCoordinator);
    }
    public UiArtifactsSidecar uiSidecar() { return collaborators.uiSidecar(); }
    public void setUiSidecar(UiArtifactsSidecar uiSidecar) { collaborators.setUiSidecar(uiSidecar); }

    public Map<String, Object> pythonCfg() { return configSlices.pythonCfg(); }
    public void setPythonCfg(Map<String, Object> pythonCfg) { configSlices.setPythonCfg(pythonCfg); }
    public Map<String, Object> geometryCfg() { return configSlices.geometryCfg(); }
    public void setGeometryCfg(Map<String, Object> geometryCfg) { configSlices.setGeometryCfg(geometryCfg); }
    public Map<String, Object> positioningCfg() { return configSlices.positioningCfg(); }
    public void setPositioningCfg(Map<String, Object> positioningCfg) { configSlices.setPositioningCfg(positioningCfg); }
    public Map<String, Object> uiCfg() { return configSlices.uiCfg(); }
    public void setUiCfg(Map<String, Object> uiCfg) { configSlices.setUiCfg(uiCfg); }
}
