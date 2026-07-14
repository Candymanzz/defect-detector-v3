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
            return new InspectionDecision(cameraId, frameId, false, "CAPTURE", 0.0, "NO_REFERENCE", "SKIPPED");
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
        InspectionDecision decision = new InspectionDecision(cameraId, frameId, overallPass, action, anomalyScore, pyStatus, geometryStatus);
        if (log != null) {
            log.info(
                    "inspection_decision cam={} frame={} overall={} action={} py_ok={} py_status={} "
                            + "anomaly={} threshold={} geom_pass={} geom_status={} align_pass={} wrinkles={} joint={}",
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
                    geomResp == null || geomResp.header() == null ? null : geomResp.header().get("jointDefectMm")
            );
        }
        return decision;
    }

    private static boolean isCaptureOnlyWithoutReference(BinaryProtocol.Message pyResp, BinaryProtocol.Message geomResp) {
        if (!isSkippedBecauseNoReference(pyResp)) {
            return false;
        }
        return geomResp == null || isSkippedBecauseNoReference(geomResp);
    }

    private static boolean isSkippedBecauseNoReference(BinaryProtocol.Message response) {
        if (response == null || response.header() == null) {
            return false;
        }
        if (!"SKIPPED".equals(String.valueOf(response.header().getOrDefault("status", "")))) {
            return false;
        }
        return String.valueOf(response.header().getOrDefault("error", "")).contains("no reference");
    }
}
