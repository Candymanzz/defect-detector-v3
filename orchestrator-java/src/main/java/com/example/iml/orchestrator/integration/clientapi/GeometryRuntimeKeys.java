package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.Map;
import java.util.Set;

/** Key normalization and geometry-header apply helpers for {@link GeometryRuntimeConfig}. */
final class GeometryRuntimeKeys {

    static final String DEFAULT_PROFILE = "__default__";

    static final Set<String> HEADER_KEYS = Set.of(
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
            "threshold",
            "jointThreshold"
    );

    private GeometryRuntimeKeys() {
    }

    static String normalizeKey(Object rawKey) {
        if (rawKey == null) {
            return null;
        }
        String k = String.valueOf(rawKey).trim();
        if (k.isEmpty()) {
            return null;
        }
        if (HEADER_KEYS.contains(k)) {
            return k;
        }
        return switch (k) {
            case "main_roi" -> "mainRoi";
            case "main_roi_polygon_norm" -> "mainRoiPolygonNorm";
            case "roi_polygon_norm" -> "mainRoiPolygonNorm";
            case "interest_polygon_norm" -> "mainRoiPolygonNorm";
            case "joint_roi" -> "jointRoi";
            case "joint_mode" -> "jointMode";
            case "wrinkles_roi" -> "wrinklesRoi";
            case "pixels_to_mm" -> "pixelsToMm";
            case "max_shift_mm" -> "maxShiftMm";
            case "max_rotation_deg" -> "maxRotationDeg";
            case "max_concentricity_mm" -> "maxConcentricityMm";
            case "max_joint_defect_mm" -> "maxJointDefectMm";
            case "joint_min_width_mm" -> "jointMinWidthMm";
            case "joint_max_width_mm" -> "jointMaxWidthMm";
            case "max_joint_parallelism_deg" -> "maxJointParallelismDeg";
            case "joint_threshold", "jointThreshold" -> "jointThreshold";
            case "max_wrinkles_score" -> "maxWrinklesScore";
            case "fallback_threshold", "inspection_threshold", "sensitivity" -> "threshold";
            default -> null;
        };
    }

    static String normalizeProfile(String analysisProfile) {
        if (analysisProfile == null) {
            return DEFAULT_PROFILE;
        }
        String trimmed = analysisProfile.trim();
        return trimmed.isEmpty() ? DEFAULT_PROFILE : trimmed;
    }

    static void applyGeometryEntry(Map<String, Object> header, String key, Object value) {
        if ("wrinklesRoi".equals(key)) {
            return;
        }
        if ("threshold".equals(key)) {
            double t = YamlScalars.toDouble(value, 0.25);
            header.put("threshold", t);
            header.put("maxWrinklesScore", t);
            return;
        }
        if ("mainRoi".equals(key)) {
            header.put("mainRoi", value);
            header.put("wrinklesRoi", value);
            return;
        }
        if ("jointThreshold".equals(key) || "maxJointDefectMm".equals(key)) {
            header.put("maxJointDefectMm", YamlScalars.toDouble(value, 0.3));
            return;
        }
        if ("maxConcentricityMm".equals(key)) {
            return;
        }
        header.put(key, value);
    }

    static void putIfPresent(Map<String, Object> source, Map<String, Object> target, String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }

    static double defaultPythonThreshold(Map<String, Object> pythonYaml) {
        return YamlScalars.toDouble(pythonYaml == null ? null : pythonYaml.get("fallback_threshold"), 0.25);
    }
}
