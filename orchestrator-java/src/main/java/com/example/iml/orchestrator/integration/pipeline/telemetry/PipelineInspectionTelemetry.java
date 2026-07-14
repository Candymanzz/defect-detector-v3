package com.example.iml.orchestrator.integration.pipeline.telemetry;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.spi.PipelineRunTelemetry;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Запись структурированных событий пайплайна в {@link PipelineStagesLog} (отдельно от бизнес-логики стадий).
 */
public final class PipelineInspectionTelemetry implements PipelineRunTelemetry {

    @Override
    public void logReferenceSnapshot(
            PipelineStagesLog pipelineStagesLog,
            int cameraId,
            String productType,
            long referenceWallMs,
            int setReferenceRepeats,
            Map<String, Object> extras
    ) {
        if (pipelineStagesLog == null) {
            return;
        }
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("event", "reference_capture");
        row.put("camera_id", cameraId);
        row.put("product_type", productType);
        row.put("reference_wall_ms", referenceWallMs);
        row.put("reference_wall_s", referenceWallMs / 1000.0);
        row.put("reference_repeats_applied", setReferenceRepeats);
        if (extras != null && !extras.isEmpty()) {
            row.putAll(extras);
        }
        pipelineStagesLog.append(row);
    }

    @Override
    public void logInspectionCycle(
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
    ) {
        if (pipelineStagesLog == null) {
            return;
        }
        Map<String, Object> pyHeader = state.py() == null ? Map.of() : state.py().header();
        Map<String, Object> capHeader = state.capture() == null || state.capture().header() == null
                ? Map.of()
                : state.capture().header();
        long decisionMs = YamlScalars.nanosToMs(tDecisionEndNanos - tDecisionStartNanos);
        long fanoutMs = YamlScalars.nanosToMs(tFanoutEndNanos - tDecisionEndNanos);
        double pyStageAlignMs = YamlScalars.toDouble(pyHeader.get("stage_ms_align"), 0.0);
        double pyStageDiffMs = YamlScalars.toDouble(pyHeader.get("stage_ms_diff"), 0.0);
        double pyStageAnomalyMs = YamlScalars.toDouble(pyHeader.get("stage_ms_anomaly"), 0.0);
        double pyStageFpRecheckMs = YamlScalars.toDouble(pyHeader.get("stage_ms_fp_recheck"), 0.0);
        double pyStageEncodeMs = YamlScalars.toDouble(pyHeader.get("stage_ms_encode"), 0.0);
        double pyStageTotalMs = YamlScalars.toDouble(pyHeader.get("stage_ms_total"), 0.0);
        long positioningMs = YamlScalars.toLong(capHeader.get("positioning_ms"), 0L);

        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("event", "inspection_cycle");
        if (timingExtras != null && !timingExtras.isEmpty()) {
            row.putAll(timingExtras);
        }
        row.put("camera_id", cameraId);
        row.put("product_type", productType);
        row.put("detector_id", detectorId);
        row.put("frame_id", decision.frameId());
        row.put("reference_ms", referenceMsFinal);
        row.put("capture_ms", state.captureMs());
        row.put("positioning_ms", positioningMs);
        row.put("positioning_s", positioningMs / 1000.0);
        row.put("positioning_orb_ms", YamlScalars.toDouble(capHeader.get("positioning_stage_ms_orb"), 0.0));
        row.put("positioning_warp_ms", YamlScalars.toDouble(capHeader.get("positioning_stage_ms_warp"), 0.0));
        row.put("positioning_ecc_ms", YamlScalars.toDouble(capHeader.get("positioning_stage_ms_ecc"), 0.0));
        row.put("positioning_write_ms", YamlScalars.toDouble(capHeader.get("positioning_stage_ms_write"), 0.0));
        row.put("python_ms", state.pythonMs());
        row.put("geometry_ms", state.geometryMs());
        row.put("capture_s", state.captureMs() / 1000.0);
        row.put("geometry_s", state.geometryMs() / 1000.0);
        row.put("python_worker_s", state.pythonMs() / 1000.0);
        row.put("decision_ms", decisionMs);
        row.put("decision_s", decisionMs / 1000.0);
        row.put("fanout_ms", fanoutMs);
        row.put("fanout_s", fanoutMs / 1000.0);
        row.put("pipeline_wall_since_cam_thread_start_ms", pipelineWallSinceCamThreadStartMs);
        row.put("pipeline_wall_since_cam_thread_start_s", pipelineWallSinceCamThreadStartMs / 1000.0);
        row.put("py_align_ms", pyStageAlignMs);
        row.put("py_align_s", pyStageAlignMs / 1000.0);
        row.put("py_diff_ms", pyStageDiffMs);
        row.put("py_diff_s", pyStageDiffMs / 1000.0);
        row.put("py_anomaly_ms", pyStageAnomalyMs);
        row.put("py_anomaly_s", pyStageAnomalyMs / 1000.0);
        row.put("py_fp_recheck_ms", pyStageFpRecheckMs);
        row.put("py_fp_recheck_s", pyStageFpRecheckMs / 1000.0);
        row.put("py_encode_ms", pyStageEncodeMs);
        row.put("py_encode_s", pyStageEncodeMs / 1000.0);
        row.put("py_reported_total_ms", pyStageTotalMs);
        row.put("py_reported_total_s", pyStageTotalMs / 1000.0);
        row.put("overall_pass", decision.overallPass());
        row.put("action", decision.action());
        row.put("anomaly_score", decision.anomalyScore());
        row.put("python_status", decision.pythonStatus());
        row.put("geometry_status", decision.geometryStatus());
        if (state.geom() != null && state.geom().header() != null) {
            Map<String, Object> gh = state.geom().header();
            row.put("geometry_service_status", String.valueOf(gh.getOrDefault("status", "")));
            row.put("geometry_overall_pass", gh.get("overallPass"));
            row.put("geometry_homography_rows", gh.get("homographyRefToCurrent") != null ? "present" : "absent");
        }
        if (state.py() != null && state.py().header() != null) {
            Map<String, Object> ph = state.py().header();
            row.put("python_service_ok", ph.get("ok"));
            row.put("python_service_status", String.valueOf(ph.getOrDefault("status", "")));
        }
        pipelineStagesLog.append(row);
        appendInspectionTimingEvent(
                pipelineStagesLog,
                cameraId,
                productType,
                detectorId,
                decision.frameId(),
                timingExtras
        );
    }

    private static void appendInspectionTimingEvent(
            PipelineStagesLog pipelineStagesLog,
            int cameraId,
            String productType,
            String detectorId,
            long frameId,
            Map<String, Object> timingExtras
    ) {
        if (timingExtras == null || timingExtras.isEmpty()) {
            return;
        }
        if (!timingExtras.containsKey("capture_to_geometry_done_ms")
                && !timingExtras.containsKey("capture_to_python_done_ms")
                && !timingExtras.containsKey("capture_frame_to_inspection_end_ms")) {
            return;
        }
        LinkedHashMap<String, Object> timingRow = new LinkedHashMap<>();
        timingRow.put("event", "inspection_timing");
        timingRow.put("camera_id", cameraId);
        timingRow.put("product_type", productType);
        timingRow.put("detector_id", detectorId);
        timingRow.put("frame_id", frameId);
        if (timingExtras.containsKey("capture_to_geometry_done_ms")) {
            Object v = timingExtras.get("capture_to_geometry_done_ms");
            timingRow.put("capture_to_geometry_done_ms", v);
            timingRow.put("capture_to_geometry_done_s", YamlScalars.toDouble(v, 0.0) / 1000.0);
        }
        if (timingExtras.containsKey("capture_to_python_done_ms")) {
            Object v = timingExtras.get("capture_to_python_done_ms");
            timingRow.put("capture_to_python_done_ms", v);
            timingRow.put("capture_to_python_done_s", YamlScalars.toDouble(v, 0.0) / 1000.0);
        }
        if (timingExtras.containsKey("capture_frame_to_inspection_end_ms")) {
            Object v = timingExtras.get("capture_frame_to_inspection_end_ms");
            timingRow.put("capture_frame_to_inspection_end_ms", v);
            timingRow.put("capture_frame_to_inspection_end_s", YamlScalars.toDouble(v, 0.0) / 1000.0);
        }
        pipelineStagesLog.append(timingRow);
    }
}
