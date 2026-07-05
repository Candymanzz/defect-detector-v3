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
    private static final String DEFAULT_PROFILE = "__default__";

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

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> overridesByProfile = new ConcurrentHashMap<>();

    public void clear() {
        overridesByProfile.clear();
    }

    public void clear(String analysisProfile) {
        overridesByProfile.remove(normalizeProfile(analysisProfile));
    }

    /**
     * Полная замена набора переопределений (ключи как в заголовке geometry: mainRoi, maxShiftMm, …
     * или snake_case: main_roi, max_shift_mm — нормализуются).
     */
    public void replaceAllFromClient(Map<String, Object> body) {
        replaceAllFromClient(null, body);
    }

    public void replaceAllFromClient(String analysisProfile, Map<String, Object> body) {
        String profileKey = normalizeProfile(analysisProfile);
        ConcurrentHashMap<String, Object> profileOverrides = new ConcurrentHashMap<>();
        if (body == null) {
            overridesByProfile.put(profileKey, profileOverrides);
            return;
        }
        for (Map.Entry<String, Object> e : body.entrySet()) {
            String key = normalizeKey(e.getKey());
            if (key != null && e.getValue() != null) {
                profileOverrides.put(key, e.getValue());
            }
        }
        overridesByProfile.put(profileKey, profileOverrides);
    }

    public void mergeFromClient(String analysisProfile, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        ConcurrentHashMap<String, Object> profileOverrides =
                overridesByProfile.computeIfAbsent(normalizeProfile(analysisProfile), ignored -> new ConcurrentHashMap<>());
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String key = normalizeKey(entry.getKey());
            if (key != null && entry.getValue() != null) {
                profileOverrides.put(key, entry.getValue());
            }
        }
    }

    public Map<String, Object> overridesCopy() {
        return overridesCopy(null);
    }

    public Map<String, Object> overridesCopy(String analysisProfile) {
        return new HashMap<>(profileOverrides(analysisProfile));
    }

    public void applyToGeometryHeader(Map<String, Object> header) {
        applyToGeometryHeader(header, null);
    }

    public void applyToGeometryHeader(Map<String, Object> header, String analysisProfile) {
        boolean clientReferenceBundle = YamlScalars.toBool(header.get("client_reference_bundle"), false);
        boolean hasReferenceJointRoi = header.get("jointRoi") != null;
        profileOverrides(analysisProfile).forEach((key, value) -> {
            if (clientReferenceBundle && hasReferenceJointRoi && "jointRoi".equals(key)) {
                return;
            }
            applyGeometryEntry(header, key, value);
        });
    }

    /** Порог чувствительности для python {@code inspect_shm} (поверх {@code python_detector.fallback_threshold}). */
    public void applyToPythonHeader(Map<String, Object> header, Map<String, Object> pythonYaml) {
        applyToPythonHeader(header, pythonYaml, null);
    }

    public void applyToPythonHeader(Map<String, Object> header, Map<String, Object> pythonYaml, String analysisProfile) {
        Map<String, Object> overrides = profileOverrides(analysisProfile);
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
        return resolvePythonThreshold(pythonYaml, null);
    }

    public double resolvePythonThreshold(Map<String, Object> pythonYaml, String analysisProfile) {
        Map<String, Object> overrides = profileOverrides(analysisProfile);
        if (overrides.containsKey("threshold")) {
            return YamlScalars.toDouble(overrides.get("threshold"), defaultPythonThreshold(pythonYaml));
        }
        return defaultPythonThreshold(pythonYaml);
    }

    /**
     * Сводка полей для GET {@code /api/client/geometry-runtime} (YAML + runtime overrides).
     */
    public Map<String, Object> effectiveForDisplay(Map<String, Object> yamlGeometry, Map<String, Object> pythonYaml) {
        return effectiveForDisplay(yamlGeometry, pythonYaml, null);
    }

    public Map<String, Object> effectiveForDisplay(
            Map<String, Object> yamlGeometry,
            Map<String, Object> pythonYaml,
            String analysisProfile
    ) {
        Map<String, Object> m = new LinkedHashMap<>();
        Object defaultMainRoi = yamlGeometry != null && yamlGeometry.get("main_roi") != null
                ? yamlGeometry.get("main_roi")
                : Map.of("x", 0, "y", 0, "width", 2448, "height", 2048);
        m.put("mainRoi", defaultMainRoi);
        m.put("wrinklesRoi", defaultMainRoi);
        m.put("pixelsToMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("pixels_to_mm"), 0.01));
        m.put("maxShiftMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_shift_mm"), 0.5));
        m.put("maxRotationDeg", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_rotation_deg"), 1.0));
        m.put("maxJointDefectMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_joint_defect_mm"), 0.3));
        double thresholdDefault = defaultPythonThreshold(pythonYaml);
        m.put("threshold", thresholdDefault);
        m.put("maxWrinklesScore", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_wrinkles_score"), thresholdDefault));
        applyToGeometryHeader(m, analysisProfile);
        m.put("wrinklesRoi", m.get("mainRoi"));
        return m;
    }

    private void applyGeometryEntry(Map<String, Object> header, String key, Object value) {
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

    private Map<String, Object> profileOverrides(String analysisProfile) {
        return overridesByProfile.getOrDefault(normalizeProfile(analysisProfile), new ConcurrentHashMap<>());
    }

    private static String normalizeProfile(String analysisProfile) {
        if (analysisProfile == null) {
            return DEFAULT_PROFILE;
        }
        String trimmed = analysisProfile.trim();
        return trimmed.isEmpty() ? DEFAULT_PROFILE : trimmed;
    }

    private static void putIfPresent(Map<String, Object> source, Map<String, Object> target, String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }
}
