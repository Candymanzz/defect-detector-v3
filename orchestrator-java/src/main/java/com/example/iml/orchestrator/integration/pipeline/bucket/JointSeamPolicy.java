package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.Map;

/**
 * Пороги шва этикетки для bucket-level ужесточения, когда шов плохо виден на соседних камерах.
 */
public record JointSeamPolicy(
        double siblingMinVisibility,
        double maxParallelismDegStrict,
        double minWidthMmStrict,
        double maxWidthMmStrict
) {
    public static JointSeamPolicy defaults() {
        return new JointSeamPolicy(0.25, 1.5, 0.8, 2.5);
    }

    public static JointSeamPolicy fromGeometryYaml(Map<String, Object> geometryCfg) {
        if (geometryCfg == null) {
            return defaults();
        }
        return new JointSeamPolicy(
                YamlScalars.toDouble(geometryCfg.get("joint_sibling_min_visibility"), 0.25),
                YamlScalars.toDouble(geometryCfg.get("max_joint_parallelism_deg_strict"), 1.5),
                YamlScalars.toDouble(geometryCfg.get("joint_min_width_mm_strict"), 0.4),
                YamlScalars.toDouble(geometryCfg.get("joint_max_width_mm_strict"), 2.5)
        );
    }

    public boolean passesStrict(double parallelismDeg, double widthMm) {
        return parallelismDeg <= maxParallelismDegStrict
                && widthMm >= minWidthMmStrict
                && widthMm <= maxWidthMmStrict;
    }
}
