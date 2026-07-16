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
        payload.put("jointDefectMm", response.jointDefectMm());
        payload.put("jointParallelismDeg", response.jointParallelismDeg());
        payload.put("jointWidthMm", response.jointWidthMm());
        payload.put("jointVisibility", response.jointVisibility());
        payload.put("wrinklesScore", response.wrinklesScore());
        payload.put("alignmentPass", response.alignmentPass());
        payload.put("concentricityPass", response.concentricityPass());
        payload.put("jointPass", response.jointPass());
        payload.put("wrinklesPass", response.wrinklesPass());
        payload.put("overallPass", response.overallPass());
        payload.put("status", response.status());
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
