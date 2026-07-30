package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.CoreCollaboratorsBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.context.ChildProcessesContext;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.integration.clientws.ClientWsServiceHolder;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.pipeline.stages.CaptureFrameDownscaleService;
import com.example.iml.orchestrator.integration.pipeline.stages.WorkerCaptureCoordinator;
import com.example.iml.orchestrator.integration.plc.PlcFinsServiceHolder;
import com.example.iml.orchestrator.integration.trigger.ManualLineDirectionService;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;
import com.example.iml.orchestrator.integration.ui.UiArtifactsSidecar;
import org.apache.logging.log4j.Logger;

/**
 * Только collaborator'ы capture/API/gate — без запуска child-процессов.
 */
public final class CoreCollaboratorsBootstrapImpl extends AbstractBootstrapService
        implements CoreCollaboratorsBootstrap {

    public CoreCollaboratorsBootstrapImpl(Logger log) {
        super(log);
    }

    @Override
    public void assemble(ChildProcessesContext processes) {
        var env = processes.env();
        var preflight = processes.preflight();
        processes.setUiSidecar(new UiArtifactsSidecar(log));
        processes.setGeometrySnapshotCache(new GeometrySnapshotCache());
        processes.setGeometryRuntimeConfig(new GeometryRuntimeConfig());
        processes.setInspectionGate(PerCameraInspectionGate.fromCameras(preflight.cameras()));
        processes.setManualLineDirection(new ManualLineDirectionService());
        processes.setPlcFinsHolder(new PlcFinsServiceHolder());
        ClientWsServiceHolder clientWsHolder = new ClientWsServiceHolder();
        processes.setClientWsHolder(clientWsHolder);
        processes.setClientApiMount(ClientApiMount.fromRootYaml(
                env.root(),
                processes.geometryRuntimeConfig(),
                processes.inspectionGate(),
                processes.manualLineDirection(),
                processes.plcFinsHolder(),
                clientWsHolder
        ));

        FrameJpegWriter jpegWriter = new FrameJpegWriter(log);
        IntegrationFeatureConfig.CaptureFrameDownscaleConfig captureDownscaleCfg =
                IntegrationFeatureConfig.parseCaptureFrameDownscale(preflight.integration());
        CaptureFrameDownscaleService captureDownscaleService = captureDownscaleCfg.enabled()
                ? new CaptureFrameDownscaleService(log, captureDownscaleCfg.scale())
                : null;
        if (captureDownscaleCfg.enabled()) {
            log.info(
                    "capture_frame_downscale enabled scale={} apply_inspection={} apply_reference={} apply_client_reference={}",
                    captureDownscaleCfg.scale(),
                    captureDownscaleCfg.applyToInspectionCapture(),
                    captureDownscaleCfg.applyToReferenceCapture(),
                    captureDownscaleCfg.applyToClientReferenceBundle()
            );
        }
        processes.setCaptureCoordinator(new WorkerCaptureCoordinator(
                log,
                jpegWriter,
                captureDownscaleService,
                captureDownscaleCfg.applyToInspectionCapture(),
                captureDownscaleCfg.applyToReferenceCapture(),
                captureDownscaleCfg.applyToClientReferenceBundle(),
                IntegrationFeatureConfig.parseCaptureWithoutReference(preflight.integration())
        ));
    }
}
