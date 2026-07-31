package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/** Positioning RPC timing / error logs. */
final class InspectPositioningTiming {

    private InspectPositioningTiming() {
    }

    static void log(
            Logger log,
            int cameraId,
            PipelineState state,
            BinaryProtocol.Message resp,
            long wallMs
    ) {
        if (log == null) {
            return;
        }
        Map<String, Object> rh = resp == null || resp.header() == null ? Map.of() : resp.header();
        int msgType = resp == null ? -1 : resp.type();
        if (msgType != BinaryProtocol.MSG_RESPONSE) {
            log.warn(
                    "positioning_rpc_error cam={} frame={} wall_ms={} msg_type={} error={} error_class={} keys={}",
                    cameraId,
                    state.capture().header().get("frame_id"),
                    wallMs,
                    msgType,
                    rh.get("error"),
                    rh.get("error_class"),
                    rh.keySet()
            );
        }
        log.info(
                "positioning_timing cam={} frame={} wall_ms={} service_ms={} orb_ms={} warp_ms={} ecc_ms={} write_ms={} "
                        + "status={} shift=({}, {}) rot={} aligned={} "
                        + "raw_abs={} coarse_abs={} orb_abs={} final_abs={} final_ncc={} residual=({}, {}) "
                        + "coarse_shift=({}, {}) orb_good={} orb_inliers={} ecc_ok={} ecc_cc={}",
                cameraId,
                state.capture().header().get("frame_id"),
                wallMs,
                YamlScalars.toDouble(rh.get("stage_ms_total"), wallMs),
                YamlScalars.toDouble(rh.get("stage_ms_orb"), 0.0),
                YamlScalars.toDouble(rh.get("stage_ms_warp"), 0.0),
                YamlScalars.toDouble(rh.get("stage_ms_ecc"), 0.0),
                YamlScalars.toDouble(rh.get("stage_ms_write"), 0.0),
                rh.getOrDefault("status", "?"),
                rh.get("shiftXmm"),
                rh.get("shiftYmm"),
                rh.get("rotationDeg"),
                rh.get("alignedWritten"),
                rh.get("diag_raw_mean_absdiff"),
                rh.get("diag_coarse_mean_absdiff"),
                rh.get("diag_orb_mean_absdiff"),
                rh.get("diag_final_mean_absdiff"),
                rh.get("diag_final_ncc"),
                rh.get("diag_final_residual_dx"),
                rh.get("diag_final_residual_dy"),
                rh.get("diag_coarse_dx_px"),
                rh.get("diag_coarse_dy_px"),
                rh.get("diag_orb_good_matches"),
                rh.get("diag_orb_inliers"),
                rh.get("diag_ecc_ok"),
                rh.get("diag_ecc_cc")
        );
    }
}
