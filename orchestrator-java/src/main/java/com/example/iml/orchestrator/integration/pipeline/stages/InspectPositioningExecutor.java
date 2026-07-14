package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.capture.LineFramePinService;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.BinaryInspectHeaders;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Выравнивание кадра к эталону перед geometry/surface: ведро всегда в одной позе.
 */
public final class InspectPositioningExecutor {

    public static final String HEADER_HARD_FAIL = "positioning_hard_fail";
    public static final String HEADER_ALIGNED = "positioning_aligned";

    private final Logger log;
    private final List<? extends BinaryRpcSupervisor> positioningPool;
    private final Semaphore positioningSlots;
    private final AtomicInteger positioningRoundRobin;
    private final Map<String, Object> positioningCfg;
    private final boolean enabled;
    private final boolean failOnReject;

    public InspectPositioningExecutor(
            Logger log,
            List<? extends BinaryRpcSupervisor> positioningPool,
            Semaphore positioningSlots,
            AtomicInteger positioningRoundRobin,
            Map<String, Object> positioningCfg
    ) {
        this.log = log;
        this.positioningPool = positioningPool == null ? List.of() : List.copyOf(positioningPool);
        this.positioningSlots = positioningSlots;
        this.positioningRoundRobin = positioningRoundRobin == null ? new AtomicInteger() : positioningRoundRobin;
        this.positioningCfg = positioningCfg == null ? Map.of() : Map.copyOf(positioningCfg);
        this.enabled = YamlScalars.toBool(this.positioningCfg.get("enabled"), !this.positioningPool.isEmpty())
                && !this.positioningPool.isEmpty();
        // Large pose discrepancy is expected; hard-fail only when alignment itself failed.
        this.failOnReject = YamlScalars.toBool(this.positioningCfg.get("fail_on_reject"), true);
    }

    public static InspectPositioningExecutor disabled(Logger log) {
        return new InspectPositioningExecutor(log, List.of(), new Semaphore(0), new AtomicInteger(), Map.of("enabled", false));
    }

    public PipelineState apply(
            PipelineState state,
            int cameraId,
            String productType,
            ReferenceSnapshot activeReference,
            Map<String, Object> geometryCfg,
            Map<String, Object> pythonCfg
    ) {
        if (!enabled) {
            return state;
        }
        if (activeReference == null || activeReference.header() == null) {
            return state;
        }
        if (state == null || state.capture() == null || state.capture().header() == null) {
            return state;
        }
        BinaryRpcSupervisor positioning = positioningPool.get(
                Math.floorMod(positioningRoundRobin.getAndIncrement(), positioningPool.size())
        );
        try {
            long t0 = System.nanoTime();
            Map<String, Object> header = BinaryInspectHeaders.positioningHeader(
                    cameraId,
                    state.capture(),
                    activeReference,
                    geometryCfg,
                    positioningCfg
            );
            BinaryInspectHeaders.applyMainRoiFromPolygon(header, state.capture(), activeReference);
            if (positioningSlots != null) {
                positioningSlots.acquire();
            }
            try {
                BinaryProtocol.Message resp = positioning.command(header);
                long wallMs = YamlScalars.nanosToMs(System.nanoTime() - t0);
                if (log != null) {
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
                return applyResponse(state, resp, cameraId, wallMs);
            } finally {
                if (positioningSlots != null) {
                    positioningSlots.release();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private PipelineState applyResponse(
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
            putIfPresent(captureHeader, "positioning_status", resp.header().get("status"));
            putIfPresent(captureHeader, "positioning_shift_x_mm", resp.header().get("shiftXmm"));
            putIfPresent(captureHeader, "positioning_shift_y_mm", resp.header().get("shiftYmm"));
            putIfPresent(captureHeader, "positioning_rotation_deg", resp.header().get("rotationDeg"));
            putIfPresent(captureHeader, "positioning_stage_ms_orb", resp.header().get("stage_ms_orb"));
            putIfPresent(captureHeader, "positioning_stage_ms_warp", resp.header().get("stage_ms_warp"));
            putIfPresent(captureHeader, "positioning_stage_ms_ecc", resp.header().get("stage_ms_ecc"));
            putIfPresent(captureHeader, "positioning_stage_ms_write", resp.header().get("stage_ms_write"));
            putIfPresent(captureHeader, "positioning_stage_ms_total", resp.header().get("stage_ms_total"));
            putIfPresent(captureHeader, "positioning_homography_ref_to_cur", resp.header().get("homographyRefToCurrent"));
            putIfPresent(captureHeader, "positioning_final_absdiff", resp.header().get("diag_final_mean_absdiff"));
            putIfPresent(captureHeader, "positioning_final_ncc", resp.header().get("diag_final_ncc"));
            putIfPresent(captureHeader, "positioning_residual_dx", resp.header().get("diag_final_residual_dx"));
            putIfPresent(captureHeader, "positioning_residual_dy", resp.header().get("diag_final_residual_dy"));
            putIfPresent(captureHeader, "positioning_orb_inliers", resp.header().get("diag_orb_inliers"));
            putIfPresent(captureHeader, "positioning_ecc_cc", resp.header().get("diag_ecc_cc"));
            putIfPresent(captureHeader, "positioning_diagnostics", resp.header().get("diagnostics"));
        }

        if (alignedWritten && resp != null) {
            String outName = String.valueOf(resp.header().getOrDefault("output_shm_name", "")).trim();
            if (outName.isEmpty()) {
                outName = String.valueOf(resp.header().getOrDefault("shm_name", "")).trim();
            }
            if (!outName.isEmpty()) {
                Object previousShm = state.capture().header().get("shm_name");
                putIfPresent(captureHeader, "original_shm_name", previousShm);
                captureHeader.put("shm_name", outName.startsWith("/") ? outName : "/" + outName.replace("/", "_"));
                captureHeader.put("shm_offset", 0);
                putIfPresent(captureHeader, "width", resp.header().get("width"));
                putIfPresent(captureHeader, "height", resp.header().get("height"));
                putIfPresent(captureHeader, "stride", resp.header().get("stride"));
                captureHeader.put(HEADER_ALIGNED, true);
                // Explicit UI hint: inspection JPEG / cards must use the aligned buffer.
                captureHeader.put("ui_preview_shm_name", captureHeader.get("shm_name"));
                // Positioned buffer owns the frame now — drop the per-cycle line pin early.
                LineFramePinService.releasePinnedCapture(Map.of(
                        "shm_name", previousShm == null ? "" : previousShm,
                        "camera_id", cameraId
                ));
            }
        }

        if (resp != null && resp.header() != null) {
            putIfPresent(captureHeader, "positioning_error", resp.header().get("error"));
            putIfPresent(captureHeader, "positioning_error_class", resp.header().get("error_class"));
        }

        if (!ok && failOnReject) {
            captureHeader.put(HEADER_HARD_FAIL, true);
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

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
