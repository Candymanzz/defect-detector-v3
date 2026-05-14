package com.example.iml.orchestrator.integration.pipeline.spi;

import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;

import java.util.Map;

/** Структурированная телеметрия прогона пайплайна (без бизнес-логики стадий). */
public interface PipelineRunTelemetry {

    void logReferenceSnapshot(
            PipelineStagesLog pipelineStagesLog,
            int cameraId,
            String productType,
            long referenceWallMs,
            int setReferenceRepeats,
            Map<String, Object> extras
    );

    void logInspectionCycle(
            PipelineStagesLog pipelineStagesLog,
            Map<String, Object> timingExtras,
            int cameraId,
            String productType,
            String detectorId,
            long referenceMsFinal,
            long pipelineWallSinceCamThreadStartMs,
            PipelineState state,
            InspectionDecision decision,
            long tDecisionStartNanos,
            long tDecisionEndNanos,
            long tFanoutEndNanos
    );
}
