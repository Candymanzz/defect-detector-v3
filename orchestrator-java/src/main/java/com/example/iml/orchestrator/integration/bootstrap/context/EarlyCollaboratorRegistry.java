package com.example.iml.orchestrator.integration.bootstrap.context;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.integration.clientws.ClientWsServiceHolder;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.pipeline.stages.WorkerCaptureCoordinator;
import com.example.iml.orchestrator.integration.plc.PlcFinsServiceHolder;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;
import com.example.iml.orchestrator.integration.ui.UiArtifactsSidecar;

/**
 * Early in-process collaborators created around child-process startup.
 */
public final class EarlyCollaboratorRegistry {

    private GeometrySnapshotCache geometrySnapshotCache;
    private GeometryRuntimeConfig geometryRuntimeConfig;
    private PerCameraInspectionGate inspectionGate;
    private ManualLineDirectionService manualLineDirection;
    private PlcFinsServiceHolder plcFinsHolder;
    private ClientWsServiceHolder clientWsHolder;
    private ClientApiMount clientApiMount;
    private WorkerCaptureCoordinator captureCoordinator;
    private UiArtifactsSidecar uiSidecar;

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
}
