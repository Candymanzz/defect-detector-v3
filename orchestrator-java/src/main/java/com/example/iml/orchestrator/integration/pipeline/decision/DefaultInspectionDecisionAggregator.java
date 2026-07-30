package com.example.iml.orchestrator.integration.pipeline.decision;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

/**
 * Стандартная агрегация: python + geometry + статусы БРАК/FAIL.
 */
public final class DefaultInspectionDecisionAggregator implements InspectionDecisionPolicy {

    private final Logger log;

    public DefaultInspectionDecisionAggregator(Logger log) {
        this.log = log;
    }

    @Override
    public InspectionDecision decide(
            int cameraId,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message pyResp,
            BinaryProtocol.Message geomResp
    ) {
        long frameId = YamlScalars.toLong(capture.header().get("frame_id"), -1L);
        if (isCaptureOnlyWithoutReference(pyResp, geomResp)) {
            return InspectionDecision.captureOnly(cameraId, frameId);
        }
        double anomalyScore = pyResp == null ? 0.0 : YamlScalars.toDouble(pyResp.header().get("anomaly_score"), 0.0);
        String pyStatus = pyResp == null ? "UNKNOWN" : String.valueOf(pyResp.header().getOrDefault("status", "UNKNOWN"));
        boolean pythonPass = pyResp != null
                && pyResp.type() == BinaryProtocol.MSG_RESPONSE
                && Boolean.TRUE.equals(pyResp.header().get("ok"));
        boolean geometryPass = geomResp == null
                || (geomResp.type() == BinaryProtocol.MSG_RESPONSE
                && Boolean.TRUE.equals(geomResp.header().get("overallPass")));
        String geometryStatus = geomResp == null ? "UNKNOWN" : String.valueOf(
                geomResp.header().getOrDefault("status", geometryPass ? "PASS" : "FAIL")
        );

        boolean overallPass = pythonPass && geometryPass;
        String action = overallPass ? "ACCEPT" : "REJECT";
        boolean jointCamera = geomResp != null
                && geomResp.header() != null
                && Boolean.TRUE.equals(geomResp.header().get("jointCamera"));
        double jointParallelismDeg = geomDouble(geomResp, "jointParallelismDeg");
        double jointWidthMm = geomDouble(geomResp, "jointWidthMm");
        double jointVisibility = geomDouble(geomResp, "jointVisibility");
        boolean jointPass = geomResp == null
                || geomResp.header() == null
                || !geomResp.header().containsKey("jointPass")
                || Boolean.TRUE.equals(geomResp.header().get("jointPass"));

        InspectionDecision decision = new InspectionDecision(
                cameraId,
                frameId,
                overallPass,
                action,
                anomalyScore,
                pyStatus,
                geometryStatus,
                jointCamera,
                jointParallelismDeg,
                jointWidthMm,
                jointVisibility,
                jointPass
        );
        if (log != null) {
            log.info(
                    "inspection_decision cam={} frame={} overall={} action={} py_ok={} py_status={} "
                            + "anomaly={} threshold={} geom_pass={} geom_status={} align_pass={} wrinkles={} "
                            + "joint={} seam_par={} seam_w={} seam_vis={} joint_cam={}",
                    decision.cameraId(),
                    decision.frameId(),
                    decision.overallPass(),
                    decision.action(),
                    pythonPass,
                    decision.pythonStatus(),
                    anomalyScore,
                    pyResp == null || pyResp.header() == null ? null : pyResp.header().get("threshold"),
                    geometryPass,
                    decision.geometryStatus(),
                    geomResp == null || geomResp.header() == null ? null : geomResp.header().get("alignmentPass"),
                    geomResp == null || geomResp.header() == null ? null : geomResp.header().get("wrinklesScore"),
                    geomResp == null || geomResp.header() == null ? null : geomResp.header().get("jointDefectMm"),
                    jointParallelismDeg,
                    jointWidthMm,
                    jointVisibility,
                    jointCamera
            );
        }
        return decision;
    }

    private static double geomDouble(BinaryProtocol.Message geomResp, String key) {
        if (geomResp == null || geomResp.header() == null) {
            return 0.0;
        }
        return YamlScalars.toDouble(geomResp.header().get(key), 0.0);
    }

    private static boolean isCaptureOnlyWithoutReference(BinaryProtocol.Message pyResp, BinaryProtocol.Message geomResp) {
        if (isSkippedBecauseNoReference(pyResp)) {
            return geomResp == null || isSkippedBecauseNoReference(geomResp) || isErrorOrSkipped(geomResp);
        }
        return false;
    }

    private static boolean isErrorOrSkipped(BinaryProtocol.Message response) {
        if (response == null) {
            return true;
        }
        if (response.type() == BinaryProtocol.MSG_ERROR) {
            return true;
        }
        if (response.header() == null) {
            return false;
        }
        String status = String.valueOf(response.header().getOrDefault("status", ""));
        return "SKIPPED".equals(status) || "ERROR".equalsIgnoreCase(status);
    }

    private static boolean isSkippedBecauseNoReference(BinaryProtocol.Message response) {
        if (response == null || response.header() == null) {
            return false;
        }
        String error = String.valueOf(response.header().getOrDefault("error", ""));
        String detail = String.valueOf(response.header().getOrDefault("detail", ""));
        String combined = (error + " " + detail).toLowerCase();
        boolean missingReference = combined.contains("no reference")
                || combined.contains("reference for product_type");
        if (!missingReference) {
            return false;
        }
        String status = String.valueOf(response.header().getOrDefault("status", ""));
        // SKIPPED (локальный short-circuit) или ERROR/400 после clear_inspection_context на Python.
        return "SKIPPED".equals(status)
                || "ERROR".equalsIgnoreCase(status)
                || response.type() == BinaryProtocol.MSG_ERROR;
    }
}
