package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.capture.LineFramePinService;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/** Applies positioning RPC response onto the capture {@link PipelineState}. */
final class InspectPositioningResponseApplier {

    private InspectPositioningResponseApplier() {
    }

    static PipelineState apply(
            Logger log,
            boolean failOnReject,
            PipelineState state,
            BinaryProtocol.Message resp,
            int cameraId,
            long wallMs
    ) {
        Map<String, Object> captureHeader = new LinkedHashMap<>(state.capture().header());
        boolean ok = resp != null
                && resp.type() == BinaryProtocol.MSG_RESPONSE
                && YamlScalars.toBool(resp.header().get("overallPass"), false);
        boolean alignedWritten = resp != null
                && resp.type() == BinaryProtocol.MSG_RESPONSE
                && YamlScalars.toBool(resp.header().get("alignedWritten"), false);

        captureHeader.put("positioning_ms", wallMs);
        if (resp != null && resp.header() != null) {
            putDiagnostics(captureHeader, resp.header());
        }

        // Только при overallPass: иначе в SHM может лежать raw/битый кадр, а Python
        // получит identity H и даст anomaly=1.0. При FAIL идём с исходным capture
        // (как в комментарии java_positioning: «при неудачном align — исходный кадр»).
        if (ok && alignedWritten && resp != null) {
            remapAlignedShm(captureHeader, state, resp, cameraId);
        } else if (alignedWritten && !ok && log != null) {
            log.info(
                    "positioning fallback_to_raw cam={} frame={} status={} absdiff={} — not marking aligned for python",
                    cameraId,
                    captureHeader.get("frame_id"),
                    captureHeader.get("positioning_status"),
                    captureHeader.get("positioning_final_absdiff")
            );
        }

        if (resp != null && resp.header() != null) {
            putIfPresent(captureHeader, "positioning_error", resp.header().get("error"));
            putIfPresent(captureHeader, "positioning_error_class", resp.header().get("error_class"));
        }

        if (!ok && failOnReject) {
            captureHeader.put(InspectPositioningExecutor.HEADER_HARD_FAIL, true);
            if (log != null) {
                log.info(
                        "positioning reject cam={} frame={} shift=({}, {}) rot={} status={} msg_type={} error={}",
                        cameraId,
                        captureHeader.get("frame_id"),
                        captureHeader.get("positioning_shift_x_mm"),
                        captureHeader.get("positioning_shift_y_mm"),
                        captureHeader.get("positioning_rotation_deg"),
                        captureHeader.get("positioning_status"),
                        resp == null ? -1 : resp.type(),
                        resp == null || resp.header() == null ? null : resp.header().get("error")
                );
            }
        }

        BinaryProtocol.Message remappedCapture = new BinaryProtocol.Message(
                state.capture().type(),
                Map.copyOf(captureHeader),
                state.capture().payload()
        );
        return new PipelineState(
                remappedCapture,
                state.py(),
                state.geom(),
                state.captureMs(),
                state.pythonMs(),
                state.geometryMs()
        );
    }

    private static void putDiagnostics(Map<String, Object> captureHeader, Map<String, Object> rh) {
        putIfPresent(captureHeader, "positioning_status", rh.get("status"));
        putIfPresent(captureHeader, "positioning_shift_x_mm", rh.get("shiftXmm"));
        putIfPresent(captureHeader, "positioning_shift_y_mm", rh.get("shiftYmm"));
        putIfPresent(captureHeader, "positioning_rotation_deg", rh.get("rotationDeg"));
        putIfPresent(captureHeader, "positioning_stage_ms_orb", rh.get("stage_ms_orb"));
        putIfPresent(captureHeader, "positioning_stage_ms_warp", rh.get("stage_ms_warp"));
        putIfPresent(captureHeader, "positioning_stage_ms_ecc", rh.get("stage_ms_ecc"));
        putIfPresent(captureHeader, "positioning_stage_ms_write", rh.get("stage_ms_write"));
        putIfPresent(captureHeader, "positioning_stage_ms_total", rh.get("stage_ms_total"));
        putIfPresent(captureHeader, "positioning_homography_ref_to_cur", rh.get("homographyRefToCurrent"));
        putIfPresent(captureHeader, "positioning_final_absdiff", rh.get("diag_final_mean_absdiff"));
        putIfPresent(captureHeader, "positioning_final_ncc", rh.get("diag_final_ncc"));
        putIfPresent(captureHeader, "positioning_residual_dx", rh.get("diag_final_residual_dx"));
        putIfPresent(captureHeader, "positioning_residual_dy", rh.get("diag_final_residual_dy"));
        putIfPresent(captureHeader, "positioning_orb_inliers", rh.get("diag_orb_inliers"));
        putIfPresent(captureHeader, "positioning_ecc_cc", rh.get("diag_ecc_cc"));
        putIfPresent(captureHeader, "positioning_diagnostics", rh.get("diagnostics"));
    }

    private static void remapAlignedShm(
            Map<String, Object> captureHeader,
            PipelineState state,
            BinaryProtocol.Message resp,
            int cameraId
    ) {
        String outName = String.valueOf(resp.header().getOrDefault("output_shm_name", "")).trim();
        if (outName.isEmpty()) {
            outName = String.valueOf(resp.header().getOrDefault("shm_name", "")).trim();
        }
        if (outName.isEmpty()) {
            return;
        }
        Object previousShm = state.capture().header().get("shm_name");
        putIfPresent(captureHeader, "original_shm_name", previousShm);
        captureHeader.put("shm_name", outName.startsWith("/") ? outName : "/" + outName.replace("/", "_"));
        captureHeader.put("shm_offset", 0);
        putIfPresent(captureHeader, "width", resp.header().get("width"));
        putIfPresent(captureHeader, "height", resp.header().get("height"));
        putIfPresent(captureHeader, "stride", resp.header().get("stride"));
        captureHeader.put(InspectPositioningExecutor.HEADER_ALIGNED, true);
        // Explicit UI hint: inspection JPEG / cards must use the aligned buffer.
        captureHeader.put("ui_preview_shm_name", captureHeader.get("shm_name"));
        // Positioned buffer owns the frame now — drop the per-cycle line pin early.
        LineFramePinService.releasePinnedCapture(Map.of(
                "shm_name", previousShm == null ? "" : previousShm,
                "camera_id", cameraId
        ));
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
