package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Компактный geometry-снимок для WS / archive (без debugImageBase64 и shm).
 */
public final class GeometryUiPayload {

    private static final Set<String> KEYS = Set.of(
            "status",
            "overallPass",
            "alignmentPass",
            "concentricityPass",
            "jointPass",
            "wrinklesPass",
            "shiftXmm",
            "shiftYmm",
            "rotationDeg",
            "concentricityMm",
            "deviationRadiusMm",
            "maxShiftMm",
            "maxRotationDeg",
            "maxConcentricityMm",
            "pixelsToMm",
            "jointDefectMm",
            "jointParallelismDeg",
            "jointWidthMm",
            "jointWidthTopMm",
            "jointWidthBottomMm",
            "jointTaperMm",
            "jointVisibility",
            "wrinklesScore",
            "jointRimSkewDeg",
            "jointGapLeftMm",
            "jointGapRightMm",
            "jointGapAsymmetryMm",
            "rimSkewPass",
            "maxJointRimSkewDeg",
            "maxJointGapAsymmetryMm",
            "jointCamera",
            "poseQcFromPositioning",
            "error"
    );

    private GeometryUiPayload() {
    }

    public static Map<String, Object> fromGeomResponse(BinaryProtocol.Message geomResp) {
        if (geomResp == null || geomResp.header() == null || geomResp.header().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : KEYS) {
            Object value = geomResp.header().get(key);
            if (value != null) {
                out.put(key, value);
            }
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }
}
