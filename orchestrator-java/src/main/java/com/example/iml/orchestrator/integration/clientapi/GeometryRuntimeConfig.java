package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime ROI и пороги: {@code inspect_shm} java-geometry и {@code threshold} python-детектора.
 */
public final class GeometryRuntimeConfig {

    private static final Set<String> HEADER_KEYS = Set.of(
            "mainRoi",
            "mainRoiPolygonNorm",
            "jointRoi",
            "wrinklesRoi",
            "pixelsToMm",
            "maxShiftMm",
            "maxRotationDeg",
            "maxConcentricityMm",
            "maxJointDefectMm",
            "maxWrinklesScore",
            "threshold",
            "jointThreshold"
    );

    private final ConcurrentHashMap<String, Object> overrides = new ConcurrentHashMap<>();

    public void clear() {
        overrides.clear();
    }

    /**
     * Полная замена набора переопределений (ключи как в заголовке geometry: mainRoi, maxShiftMm, …
     * или snake_case: main_roi, max_shift_mm — нормализуются).
     */
    public void replaceAllFromClient(Map<String, Object> body) {
        overrides.clear();
        if (body == null) {
            return;
        }
        for (Map.Entry<String, Object> e : body.entrySet()) {
            String key = normalizeKey(e.getKey());
            if (key != null && e.getValue() != null) {
                overrides.put(key, e.getValue());
            }
        }
    }

    public Map<String, Object> overridesCopy() {
        return new HashMap<>(overrides);
    }

    public void applyToGeometryHeader(Map<String, Object> header) {
        overrides.forEach((key, value) -> applyGeometryEntry(header, key, value));
    }

    /** Порог чувствительности для python {@code inspect_shm} (поверх {@code python_detector.fallback_threshold}). */
    public void applyToPythonHeader(Map<String, Object> header, Map<String, Object> pythonYaml) {
        if (overrides.containsKey("threshold")) {
            header.put("threshold", YamlScalars.toDouble(overrides.get("threshold"), defaultPythonThreshold(pythonYaml)));
        }
        if (overrides.isEmpty()) {
            return;
        }
        Map<String, Object> algorithmParams = new LinkedHashMap<>();
        putIfPresent(overrides, algorithmParams, "mainRoi", "main_roi");
        putIfPresent(overrides, algorithmParams, "mainRoiPolygonNorm", "main_roi_polygon_norm");
        putIfPresent(overrides, algorithmParams, "jointRoi", "joint_roi");
        putIfPresent(overrides, algorithmParams, "wrinklesRoi", "wrinkles_roi");
        putIfPresent(overrides, algorithmParams, "pixelsToMm", "pixels_to_mm");
        putIfPresent(overrides, algorithmParams, "maxShiftMm", "max_shift_mm");
        putIfPresent(overrides, algorithmParams, "maxRotationDeg", "max_rotation_deg");
        putIfPresent(overrides, algorithmParams, "maxConcentricityMm", "max_concentricity_mm");
        putIfPresent(overrides, algorithmParams, "maxJointDefectMm", "max_joint_defect_mm");
        putIfPresent(overrides, algorithmParams, "maxWrinklesScore", "max_wrinkles_score");
        putIfPresent(overrides, algorithmParams, "jointThreshold", "joint_threshold");
        putIfPresent(overrides, algorithmParams, "threshold", "threshold");
        if (!algorithmParams.isEmpty()) {
            header.put("algorithm_params", algorithmParams);
        }
    }

    public double resolvePythonThreshold(Map<String, Object> pythonYaml) {
        if (overrides.containsKey("threshold")) {
            return YamlScalars.toDouble(overrides.get("threshold"), defaultPythonThreshold(pythonYaml));
        }
        return defaultPythonThreshold(pythonYaml);
    }

    /**
     * Сводка полей для GET {@code /api/client/geometry-runtime} (YAML + runtime overrides).
     */
    public Map<String, Object> effectiveForDisplay(Map<String, Object> yamlGeometry, Map<String, Object> pythonYaml) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mainRoi", yamlGeometry != null && yamlGeometry.get("main_roi") != null ? yamlGeometry.get("main_roi") : Map.of("x", 0, "y", 0, "width", 2448, "height", 2048));
        m.put("jointRoi", yamlGeometry == null ? null : yamlGeometry.get("joint_roi"));
        m.put("wrinklesRoi", yamlGeometry == null ? null : yamlGeometry.get("wrinkles_roi"));
        m.put("pixelsToMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("pixels_to_mm"), 0.01));
        m.put("maxShiftMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_shift_mm"), 0.5));
        m.put("maxRotationDeg", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_rotation_deg"), 1.0));
        m.put("maxConcentricityMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_concentricity_mm"), 0.2));
        double jointDefault = YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_joint_defect_mm"), 0.3);
        m.put("maxJointDefectMm", jointDefault);
        m.put("jointThreshold", jointDefault);
        double thresholdDefault = defaultPythonThreshold(pythonYaml);
        m.put("threshold", thresholdDefault);
        m.put("maxWrinklesScore", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_wrinkles_score"), thresholdDefault));
        applyToGeometryHeader(m);
        return m;
    }

    private void applyGeometryEntry(Map<String, Object> header, String key, Object value) {
        if ("threshold".equals(key)) {
            double t = YamlScalars.toDouble(value, 0.25);
            header.put("threshold", t);
            header.put("maxWrinklesScore", t);
            return;
        }
        if ("jointThreshold".equals(key) || "maxJointDefectMm".equals(key)) {
            header.put("maxJointDefectMm", YamlScalars.toDouble(value, 0.3));
            return;
        }
        header.put(key, value);
    }

    private static double defaultPythonThreshold(Map<String, Object> pythonYaml) {
        return YamlScalars.toDouble(pythonYaml == null ? null : pythonYaml.get("fallback_threshold"), 0.25);
    }

    private static String normalizeKey(Object rawKey) {
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
            case "wrinkles_roi" -> "wrinklesRoi";
            case "pixels_to_mm" -> "pixelsToMm";
            case "max_shift_mm" -> "maxShiftMm";
            case "max_rotation_deg" -> "maxRotationDeg";
            case "max_concentricity_mm" -> "maxConcentricityMm";
            case "max_joint_defect_mm" -> "maxJointDefectMm";
            case "joint_threshold", "jointThreshold" -> "jointThreshold";
            case "max_wrinkles_score" -> "maxWrinklesScore";
            case "fallback_threshold", "inspection_threshold", "sensitivity" -> "threshold";
            default -> null;
        };
    }

    private static void putIfPresent(Map<String, Object> source, Map<String, Object> target, String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }
}
