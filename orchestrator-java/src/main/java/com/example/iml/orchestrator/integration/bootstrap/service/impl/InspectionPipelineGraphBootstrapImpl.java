package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.context.ChildProcessesContext;
import com.example.iml.orchestrator.integration.bootstrap.service.api.InspectionPipelineGraphBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.context.PipelineAssemblyContext;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipeline;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.decision.DefaultInspectionDecisionAggregator;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.pipeline.reference.ReferenceSnapshotBootstrap;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectGeometryExecutor;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPositioningExecutor;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPythonExecutor;
import com.example.iml.orchestrator.integration.pipeline.telemetry.PipelineInspectionTelemetry;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Только wiring графа inspection pipeline (без освещения).
 */
public final class InspectionPipelineGraphBootstrapImpl extends AbstractBootstrapService
        implements InspectionPipelineGraphBootstrap {

    public InspectionPipelineGraphBootstrapImpl(Logger log) {
        super(log);
    }

    @Override
    public void assembleGraph(PipelineAssemblyContext assembly) {
        var processes = assembly.processes();
        var preflight = assembly.preflight();

        Semaphore positioningSlots = new Semaphore(Math.max(1, processes.positioningPool().size()));
        InspectionPipelineServices pipelineServices = getInspectionPipelineServices(processes, positioningSlots);
        assembly.setInspectionPipeline(new InspectionPipeline(pipelineServices));

        assembly.setPipelineReferenceRegistry(new PipelineReferenceRegistry());
        Map<Integer, String> detectorByCamera = new LinkedHashMap<>();
        for (Map<String, Object> camera : preflight.cameras()) {
            int cameraId = ConfiguredCameras.requireId(camera);
            detectorByCamera.put(cameraId, String.valueOf(camera.getOrDefault("detector", "v1")));
        }
        assembly.setDetectorByCamera(detectorByCamera);
        if (preflight.bootConfig().referenceSource() == ReferenceSource.CLIENT) {
            log.info("integration.reference_source=client — эталон только через client.reference_bundle (WebSocket)");
        }
    }

    private InspectionPipelineServices getInspectionPipelineServices(ChildProcessesContext processes, Semaphore positioningSlots) {
        AtomicInteger positioningRoundRobin = new AtomicInteger();
        InspectPositioningExecutor positioningExecutor = new InspectPositioningExecutor(
                log,
                processes.positioningPool(),
                positioningSlots,
                positioningRoundRobin,
                processes.positioningCfg()
        );
        PipelineInspectionTelemetry pipelineTelemetry = new PipelineInspectionTelemetry();
        ReferenceSnapshotBootstrap referenceBootstrap =
                new ReferenceSnapshotBootstrap(log, processes.captureCoordinator(), pipelineTelemetry);
        return new InspectionPipelineServices(
                log,
                new DefaultInspectionDecisionAggregator(log),
                pipelineTelemetry,
                new InspectGeometryExecutor(
                        log, processes.geometrySnapshotCache(), processes.geometryRuntimeConfig(), positioningExecutor),
                new InspectPythonExecutor(log, processes.geometryRuntimeConfig()),
                processes.captureCoordinator(),
                referenceBootstrap,
                processes.uiSidecar()
        );
    }
}
