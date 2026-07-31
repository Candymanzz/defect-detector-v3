package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Display/python header helpers for {@link GeometryRuntimeConfig}. */
final class GeometryRuntimeDisplay {

    private GeometryRuntimeDisplay() {
    }

    static Map<String, Object> effectiveForDisplay(
            Map<String, Object> yamlGeometry,
            Map<String, Object> pythonYaml,
            BiConsumer<Map<String, Object>, String> applyOverrides,
            String analysisProfile
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        Object defaultMainRoi = yamlGeometry != null && yamlGeometry.get("main_roi") != null
                ? yamlGeometry.get("main_roi")
                : Map.of("x", 0, "y", 0, "width", 1224, "height", 1024);
        m.put("mainRoi", defaultMainRoi);
        m.put("wrinklesRoi", defaultMainRoi);
        m.put("pixelsToMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("pixels_to_mm"), 0.02));
        m.put("maxShiftMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_shift_mm"), 0.5));
        m.put("maxRotationDeg", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_rotation_deg"), 1.0));
        m.put("maxJointDefectMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_joint_defect_mm"), 0.3));
        m.put("jointMinWidthMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("joint_min_width_mm"), 0.5));
        m.put("jointMaxWidthMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("joint_max_width_mm"), 3.0));
        m.put(
                "maxJointParallelismDeg",
                YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_joint_parallelism_deg"), 3.0)
        );
        double thresholdDefault = GeometryRuntimeKeys.defaultPythonThreshold(pythonYaml);
        m.put("threshold", thresholdDefault);
        m.put("maxWrinklesScore", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_wrinkles_score"), thresholdDefault));
        applyOverrides.accept(m, analysisProfile);
        m.put("wrinklesRoi", m.get("mainRoi"));
        return m;
    }

    static void applyToPythonHeader(
            Map<String, Object> header,
            Map<String, Object> pythonYaml,
            Map<String, Object> overrides
    ) {
        if (overrides.containsKey("threshold")) {
            header.put(
                    "threshold",
                    YamlScalars.toDouble(overrides.get("threshold"), GeometryRuntimeKeys.defaultPythonThreshold(pythonYaml))
            );
        }
        if (overrides.isEmpty()) {
            return;
        }
        Map<String, Object> algorithmParams = new LinkedHashMap<>();
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "mainRoi", "main_roi");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "mainRoiPolygonNorm", "main_roi_polygon_norm");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "jointRoi", "joint_roi");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "jointMode", "joint_mode");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "wrinklesRoi", "wrinkles_roi");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "pixelsToMm", "pixels_to_mm");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "maxShiftMm", "max_shift_mm");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "maxRotationDeg", "max_rotation_deg");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "maxConcentricityMm", "max_concentricity_mm");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "maxJointDefectMm", "max_joint_defect_mm");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "jointMinWidthMm", "joint_min_width_mm");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "jointMaxWidthMm", "joint_max_width_mm");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "maxJointParallelismDeg", "max_joint_parallelism_deg");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "maxWrinklesScore", "max_wrinkles_score");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "jointThreshold", "joint_threshold");
        GeometryRuntimeKeys.putIfPresent(overrides, algorithmParams, "threshold", "threshold");
        if (!algorithmParams.isEmpty()) {
            header.put("algorithm_params", algorithmParams);
        }
    }
}
