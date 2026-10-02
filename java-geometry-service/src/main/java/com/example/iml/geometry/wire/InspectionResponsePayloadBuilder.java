package com.example.iml.geometry.wire;

import com.example.iml.geometry.dto.InspectionRequest;
import com.example.iml.geometry.dto.InspectionResponse;

import java.util.HashMap;
import java.util.Map;

public final class InspectionResponsePayloadBuilder {

    private InspectionResponsePayloadBuilder() {
    }

    public static Map<String, Object> toResponseHeader(InspectionResponse response, boolean includeDebug) {
        return toResponseHeader(response, includeDebug, null);
    }

    public static Map<String, Object> toResponseHeader(
            InspectionResponse response,
            boolean includeDebug,
            InspectionRequest request
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("shiftXmm", response.shiftXmm());
        payload.put("shiftYmm", response.shiftYmm());
        payload.put("rotationDeg", response.rotationDeg());
        payload.put("homographyRefToCurrent", response.homographyRefToCurrent());
        payload.put("concentricityMm", response.concentricityMm());
        payload.put("deviationRadiusMm", response.deviationRadiusMm());
        payload.put("jointDefectMm", response.jointDefectMm());
        payload.put("jointParallelismDeg", response.jointParallelismDeg());
        payload.put("jointWidthMm", response.jointWidthMm());
        payload.put("jointWidthTopMm", response.jointWidthTopMm());
        payload.put("jointWidthBottomMm", response.jointWidthBottomMm());
        payload.put("jointTaperMm", response.jointTaperMm());
        payload.put("jointVisibility", response.jointVisibility());
        payload.put("wrinklesScore", response.wrinklesScore());
        payload.put("jointRimSkewDeg", response.jointRimSkewDeg());
        payload.put("jointGapLeftMm", response.jointGapLeftMm());
        payload.put("jointGapRightMm", response.jointGapRightMm());
        payload.put("jointGapAsymmetryMm", response.jointGapAsymmetryMm());
        payload.put("alignmentPass", response.alignmentPass());
        payload.put("concentricityPass", response.concentricityPass());
        payload.put("jointPass", response.jointPass());
        payload.put("wrinklesPass", response.wrinklesPass());
        payload.put("rimSkewPass", response.rimSkewPass());
        payload.put("overallPass", response.overallPass());
        payload.put("status", response.status());
        Map<String, Object> diagnostics = response.diagnostics();
        if (diagnostics != null && !diagnostics.isEmpty()) {
            payload.put("diagnostics", diagnostics);
            for (Map.Entry<String, Object> e : diagnostics.entrySet()) {
                String key = e.getKey();
                if ("status".equals(key)) {
                    continue;
                }
                // Promote stage timings / flags to top-level for orchestrator grep.
                if (key.startsWith("stage_ms_")
                        || key.startsWith("pass_")
                        || key.startsWith("joint_")
                        || key.startsWith("align_")
                        || key.equals("pose_locked")
                        || key.equals("polygon_mask")
                        || key.equals("debug_written")
                        || key.equals("has_joint_roi")
                        || key.equals("joint_visibility_only")) {
                    payload.put(key, e.getValue());
                }
                payload.put("diag_" + key, e.getValue());
            }
        }
        if (request != null) {
            payload.put("maxShiftMm", request.maxShiftMm());
            payload.put("maxRotationDeg", request.maxRotationDeg());
            payload.put("maxConcentricityMm", request.maxConcentricityMm());
            payload.put("pixelsToMm", request.pixelsToMm());
            payload.put("maxJointRimSkewDeg", request.maxJointRimSkewDeg());
            payload.put("maxJointGapAsymmetryMm", request.maxJointGapAsymmetryMm());
        }
        if (request != null && request.jointRoi() != null) {
            boolean visibilityOnly = request.jointVisibilityOnly();
            payload.put("jointMode", visibilityOnly ? "visibility" : "full");
            payload.put("jointCamera", !visibilityOnly);
        } else {
            payload.put("jointMode", "full");
            payload.put("jointCamera", false);
        }
        if (includeDebug) {
            payload.put("debugImageBase64", response.debugImageBase64());
        }
        return payload;
    }
}
