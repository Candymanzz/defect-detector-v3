package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.pipeline.decision.InspectionDecisionPolicy;
import com.example.iml.orchestrator.integration.pipeline.reference.ReferenceSnapshotBootstrap;
import com.example.iml.orchestrator.integration.pipeline.spi.CameraCaptureStage;
import com.example.iml.orchestrator.integration.pipeline.spi.FanOutEventFactory;
import com.example.iml.orchestrator.integration.pipeline.spi.GeometryInspectStage;
import com.example.iml.orchestrator.integration.pipeline.spi.PipelineRunTelemetry;
import com.example.iml.orchestrator.integration.pipeline.spi.PythonInspectStage;
import com.example.iml.orchestrator.integration.pipeline.spi.AfterInspectionSidecar;
import org.apache.logging.log4j.Logger;

/**
 * Зависимости пайплайна (DIP: координатор зависит от интерфейсов, не от конкретных исполнителей).
 */
public record InspectionPipelineServices(
        Logger log,
        InspectionDecisionPolicy decisionPolicy,
        PipelineRunTelemetry pipelineTelemetry,
        GeometryInspectStage geometryStage,
        PythonInspectStage pythonStage,
        CameraCaptureStage captureStage,
        FanOutEventFactory fanOutEventFactory,
        ReferenceSnapshotBootstrap referenceBootstrap,
        AfterInspectionSidecar afterInspectionSidecar
) {
}
