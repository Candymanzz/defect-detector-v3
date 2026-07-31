package com.example.iml.orchestrator.integration.clientapi.analissurface;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Algorithm-params merge helpers for SHM frame HTTP payloads. */
final class ShmFrameAlgorithmParams {

    static final Set<String> ALGORITHM_PARAM_KEYS = Set.of(
            "mainRoi",
            "mainRoiPolygonNorm",
            "jointRoi",
            "jointMode",
            "wrinklesRoi",
            "pixelsToMm",
            "maxShiftMm",
            "maxRotationDeg",
            "maxConcentricityMm",
            "maxJointDefectMm",
            "jointMinWidthMm",
            "jointMaxWidthMm",
            "maxJointParallelismDeg",
            "maxWrinklesScore",
            "jointThreshold",
            "threshold",
            "main_roi",
            "main_roi_polygon_norm",
            "joint_roi",
            "joint_mode",
            "wrinkles_roi",
            "pixels_to_mm",
            "max_shift_mm",
            "max_rotation_deg",
            "max_concentricity_mm",
            "max_joint_defect_mm",
            "joint_min_width_mm",
            "joint_max_width_mm",
            "max_joint_parallelism_deg",
            "max_wrinkles_score"
    );

    private ShmFrameAlgorithmParams() {
    }

    static void appendAlgorithmParams(Map<String, Object> body, Map<String, Object> header) {
        Map<String, Object> params = new LinkedHashMap<>();
        Object explicit = header.get("algorithm_params");
        if (explicit instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    params.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        for (String key : ALGORITHM_PARAM_KEYS) {
            Object value = header.get(key);
            if (value != null) {
                params.put(key, value);
            }
        }
        if (params.isEmpty()) {
            return;
        }
        body.put("algorithm_params", params);
        // Backward compatibility: existing FastAPI handlers may still read flat keys.
        Set<String> protectedKeys = new HashSet<>(Set.of(
                "product_type",
                "points",
                "shm_name",
                "width",
                "height",
                "stride",
                "shm_offset",
                "detector_id"
        ));
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!protectedKeys.contains(entry.getKey())) {
                body.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
    }
}
