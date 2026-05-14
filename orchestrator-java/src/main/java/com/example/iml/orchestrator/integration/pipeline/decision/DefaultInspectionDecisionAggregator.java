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
        double anomalyScore = pyResp == null ? 0.0 : YamlScalars.toDouble(pyResp.header().get("anomaly_score"), 0.0);
        String pyStatus = pyResp == null ? "UNKNOWN" : String.valueOf(pyResp.header().getOrDefault("status", "UNKNOWN"));
        boolean pythonPass = pyResp == null || pyResp.type() != BinaryProtocol.MSG_ERROR;
        boolean geometryPass = geomResp == null
                || (geomResp.type() != BinaryProtocol.MSG_ERROR && Boolean.TRUE.equals(geomResp.header().get("overallPass")));
        String geometryStatus = geomResp == null ? "UNKNOWN" : String.valueOf(geomResp.header().getOrDefault("status", "PASS"));

        boolean overallPass = pythonPass
                && geometryPass
                && !("БРАК".equalsIgnoreCase(pyStatus) || "FAIL".equalsIgnoreCase(pyStatus));
        String action = overallPass ? "ACCEPT" : "REJECT";
        InspectionDecision decision = new InspectionDecision(cameraId, frameId, overallPass, action, anomalyScore, pyStatus, geometryStatus);
        if (log.isDebugEnabled()) {
            log.debug("decision cam={} frame={} overall={} action={} pyStatus={} geomStatus={} score={}",
                    decision.cameraId(),
                    decision.frameId(),
                    decision.overallPass(),
                    decision.action(),
                    decision.pythonStatus(),
                    decision.geometryStatus(),
                    decision.anomalyScore());
        }
        return decision;
    }
}
