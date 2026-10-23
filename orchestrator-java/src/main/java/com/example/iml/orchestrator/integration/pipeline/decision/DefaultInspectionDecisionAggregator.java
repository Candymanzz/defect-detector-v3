package com.example.iml.orchestrator.integration.pipeline.decision;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.GeometryUiPayload;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Стандартная агрегация: python + geometry + статусы БРАК/FAIL.
 */
public final class DefaultInspectionDecisionAggregator implements InspectionDecisionPolicy {

    private static final List<String> DETECTOR_DETAIL_KEYS = List.of("reason", "area_percent", "threshold", "lines", "seam");

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
        boolean geometryPass = isGeometryPass(geomResp);
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
                jointPass,
                GeometryUiPayload.fromGeomResponse(geomResp),
                detectorDetails(pyResp)
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
                    decision.hasAnomalyScore() ? anomalyScore : null,
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

    /** Нативные поля cppAnalisSurface, нужные UI для объяснения вердикта (причина, стык/кромка); у Python-детектора пусто. */
    private static Map<String, Object> detectorDetails(BinaryProtocol.Message pyResp) {
        if (pyResp == null || pyResp.header() == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : DETECTOR_DETAIL_KEYS) {
            Object value = pyResp.header().get(key);
            if (value instanceof String text ? !text.isBlank() : value != null) {
                out.put(key, value);
            }
        }
        return out;
    }

    private static boolean isGeometryPass(BinaryProtocol.Message geomResp) {
        if (geomResp == null) {
            return false;
        }
        if (geomResp.header() != null
                && "SKIPPED".equals(String.valueOf(geomResp.header().getOrDefault("status", "")))) {
            return true;
        }
        return geomResp.type() == BinaryProtocol.MSG_RESPONSE
                && Boolean.TRUE.equals(geomResp.header().get("overallPass"));
    }

    private static double geomDouble(BinaryProtocol.Message geomResp, String key) {
        if (geomResp == null || geomResp.header() == null) {
            return 0.0;
        }
        return YamlScalars.toDouble(geomResp.header().get(key), 0.0);
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
