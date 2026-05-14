package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Переопределения ROI и порогов для заголовка {@code inspect_shm} java-geometry (поверх {@code java_geometry} в YAML).
 */
public final class GeometryRuntimeConfig {

    private static final Set<String> HEADER_KEYS = Set.of(
            "mainRoi",
            "jointRoi",
            "wrinklesRoi",
            "pixelsToMm",
            "maxShiftMm",
            "maxRotationDeg",
            "maxConcentricityMm",
            "maxJointDefectMm",
            "maxWrinklesScore"
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
        overrides.forEach(header::put);
    }

    /**
     * Сводка полей ROI/порогов как для заголовка geometry (YAML + runtime), только для ответа GET.
     */
    public Map<String, Object> effectiveForDisplay(Map<String, Object> yamlGeometry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mainRoi", yamlGeometry != null && yamlGeometry.get("main_roi") != null ? yamlGeometry.get("main_roi") : Map.of("x", 0, "y", 0, "width", 2448, "height", 2048));
        m.put("jointRoi", yamlGeometry == null ? null : yamlGeometry.get("joint_roi"));
        m.put("wrinklesRoi", yamlGeometry == null ? null : yamlGeometry.get("wrinkles_roi"));
        m.put("pixelsToMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("pixels_to_mm"), 0.01));
        m.put("maxShiftMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_shift_mm"), 0.5));
        m.put("maxRotationDeg", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_rotation_deg"), 1.0));
        m.put("maxConcentricityMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_concentricity_mm"), 0.2));
        m.put("maxJointDefectMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_joint_defect_mm"), 0.3));
        m.put("maxWrinklesScore", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_wrinkles_score"), 0.05));
        applyToGeometryHeader(m);
        return m;
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
            case "joint_roi" -> "jointRoi";
            case "wrinkles_roi" -> "wrinklesRoi";
            case "pixels_to_mm" -> "pixelsToMm";
            case "max_shift_mm" -> "maxShiftMm";
            case "max_rotation_deg" -> "maxRotationDeg";
            case "max_concentricity_mm" -> "maxConcentricityMm";
            case "max_joint_defect_mm" -> "maxJointDefectMm";
            case "max_wrinkles_score" -> "maxWrinklesScore";
            default -> null;
        };
    }
}
