package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/** Единая строка stage_timing (раньше дублировалась в benchmark и async-решении). */
public final class InspectionStageTimingLogger {

    private InspectionStageTimingLogger() {
    }

    public static void logStageTiming(
            Logger log,
            int cameraId,
            InspectionDecision decision,
            long tCameraStartNanos,
            long referenceMsFinal,
            PipelineState state,
            long tDecisionStartNanos,
            long tDecisionEndNanos,
            long tFanoutEndNanos
    ) {
        Map<String, Object> pyHeader = state.py() == null ? Map.of() : state.py().header();
        long decisionMs = YamlScalars.nanosToMs(tDecisionEndNanos - tDecisionStartNanos);
        long fanoutMs = YamlScalars.nanosToMs(tFanoutEndNanos - tDecisionEndNanos);
        long pipelineMs = state.captureMs() + state.geometryMs() + state.pythonMs() + decisionMs + fanoutMs;
        log.info("stage_timing cam={} frame={} total_ms={} pipeline_ms={} reference_ms={} capture_ms={} python_ms={} geometry_ms={} decision_ms={} fanout_ms={} "
                        + "py_align_ms={} py_diff_ms={} py_anomaly_ms={} py_fp_recheck_ms={} py_encode_ms={} py_total_ms={}",
                cameraId,
                decision.frameId(),
                YamlScalars.nanosToMs(tFanoutEndNanos - tCameraStartNanos),
                pipelineMs,
                referenceMsFinal,
                state.captureMs(),
                state.pythonMs(),
                state.geometryMs(),
                decisionMs,
                fanoutMs,
                YamlScalars.toDouble(pyHeader.get("stage_ms_align"), 0.0),
                YamlScalars.toDouble(pyHeader.get("stage_ms_diff"), 0.0),
                YamlScalars.toDouble(pyHeader.get("stage_ms_anomaly"), 0.0),
                YamlScalars.toDouble(pyHeader.get("stage_ms_fp_recheck"), 0.0),
                YamlScalars.toDouble(pyHeader.get("stage_ms_encode"), 0.0),
                YamlScalars.toDouble(pyHeader.get("stage_ms_total"), 0.0));
    }
}
