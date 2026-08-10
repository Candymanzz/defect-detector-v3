package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime ROI и пороги: {@code inspect_shm} java-geometry и {@code threshold} python-детектора.
 * При наличии {@link GeometryRuntimeStore} overrides пишутся в JSON и поднимаются после рестарта.
 */
public final class GeometryRuntimeConfig {
    private static final Logger LOG = LogManager.getLogger(GeometryRuntimeConfig.class);
    private static final String DEFAULT_PROFILE = "__default__";

    private static final Set<String> HEADER_KEYS = Set.of(
            "mainRoi",
            "mainRoiPolygonNorm",
            "jointRoi",
            "jointRoiPolygonNorm",
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
            "maxJointTaperMm",
            "jointSeamSegmentationEnabled",
            "jointSeamSegmentationSensitivity",
            "maxWrinklesScore",
            "threshold",
            "jointThreshold"
    );

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> overridesByProfile = new ConcurrentHashMap<>();
    private final GeometryRuntimeStore store;

    public GeometryRuntimeConfig() {
        this(null);
    }

    public GeometryRuntimeConfig(GeometryRuntimeStore store) {
        this.store = store;
        hydrateFromStore();
    }

    public void clear() {
        overridesByProfile.clear();
        persistClearAll();
    }

    public void clear(String analysisProfile) {
        String profileKey = normalizeProfile(analysisProfile);
        overridesByProfile.remove(profileKey);
        persistRemove(profileKey);
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
        if (body != null) {
            for (Map.Entry<String, Object> e : body.entrySet()) {
                String key = normalizeKey(e.getKey());
                if (key != null && e.getValue() != null) {
                    profileOverrides.put(key, e.getValue());
                }
            }
        }
        overridesByProfile.put(profileKey, profileOverrides);
        persistReplace(profileKey, profileOverrides);
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
        persistReplace(normalizeProfile(analysisProfile), profileOverrides);
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
        header.put("jointSeamSegmentationEnabled", true);
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
        putIfPresent(overrides, algorithmParams, "jointRoiPolygonNorm", "joint_roi_polygon_norm");
        putIfPresent(overrides, algorithmParams, "jointMode", "joint_mode");
        putIfPresent(overrides, algorithmParams, "wrinklesRoi", "wrinkles_roi");
        putIfPresent(overrides, algorithmParams, "pixelsToMm", "pixels_to_mm");
        putIfPresent(overrides, algorithmParams, "maxShiftMm", "max_shift_mm");
        putIfPresent(overrides, algorithmParams, "maxRotationDeg", "max_rotation_deg");
        putIfPresent(overrides, algorithmParams, "maxConcentricityMm", "max_concentricity_mm");
        putIfPresent(overrides, algorithmParams, "maxJointDefectMm", "max_joint_defect_mm");
        putIfPresent(overrides, algorithmParams, "jointMinWidthMm", "joint_min_width_mm");
        putIfPresent(overrides, algorithmParams, "jointMaxWidthMm", "joint_max_width_mm");
        putIfPresent(overrides, algorithmParams, "maxJointParallelismDeg", "max_joint_parallelism_deg");
        putIfPresent(overrides, algorithmParams, "maxJointTaperMm", "max_joint_taper_mm");
        putIfPresent(overrides, algorithmParams, "jointSeamSegmentationSensitivity", "joint_seam_segmentation_sensitivity");
        putIfPresent(overrides, algorithmParams, "maxWrinklesScore", "max_wrinkles_score");
        putIfPresent(overrides, algorithmParams, "jointThreshold", "joint_threshold");
        putIfPresent(overrides, algorithmParams, "threshold", "threshold");
        algorithmParams.put("joint_seam_segmentation_enabled", true);
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
                : Map.of("x", 0, "y", 0, "width", 1224, "height", 1024);
        m.put("mainRoi", defaultMainRoi);
        m.put("wrinklesRoi", defaultMainRoi);
        m.put("pixelsToMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("pixels_to_mm"), 0.02));
        m.put("maxShiftMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_shift_mm"), 0.5));
        m.put("maxRotationDeg", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_rotation_deg"), 1.0));
        m.put("maxJointDefectMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_joint_defect_mm"), 0.5));
        m.put("jointMinWidthMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("joint_min_width_mm"), 0.25));
        m.put("jointMaxWidthMm", YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("joint_max_width_mm"), 3.0));
        m.put(
                "maxJointParallelismDeg",
                YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_joint_parallelism_deg"), 5.0)
        );
        m.put(
                "maxJointTaperMm",
                YamlScalars.toDouble(yamlGeometry == null ? null : yamlGeometry.get("max_joint_taper_mm"), 0.8)
        );
        m.put("jointSeamSegmentationEnabled", true);
        m.put(
                "jointSeamSegmentationSensitivity",
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                YamlScalars.toDouble(
                                        yamlGeometry == null
                                                ? null
                                                : yamlGeometry.get("joint_seam_segmentation_sensitivity"),
                                        0.5
                                )
                        )
                )
        );
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
        if ("jointSeamSegmentationEnabled".equals(key)) {
            header.put(key, true);
            return;
        }
        if ("jointSeamSegmentationSensitivity".equals(key)) {
            double s = YamlScalars.toDouble(value, 0.5);
            if (s < 0.0) {
                s = 0.0;
            } else if (s > 1.0) {
                s = 1.0;
            }
            header.put(key, s);
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
            case "joint_roi_polygon_norm" -> "jointRoiPolygonNorm";
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
            case "max_joint_taper_mm" -> "maxJointTaperMm";
            case "joint_seam_segmentation_enabled" -> "jointSeamSegmentationEnabled";
            case "joint_seam_segmentation_sensitivity" -> "jointSeamSegmentationSensitivity";
            case "joint_threshold", "jointThreshold" -> "jointThreshold";
            case "max_wrinkles_score" -> "maxWrinklesScore";
            case "fallback_threshold", "inspection_threshold", "sensitivity" -> "threshold";
            default -> null;
        };
    }

    private Map<String, Object> profileOverrides(String analysisProfile) {
        return overridesByProfile.getOrDefault(normalizeProfile(analysisProfile), new ConcurrentHashMap<>());
    }

    private void hydrateFromStore() {
        if (store == null) {
            return;
        }
        for (Map.Entry<String, Map<String, Object>> entry : store.allProfiles().entrySet()) {
            ConcurrentHashMap<String, Object> profileOverrides = new ConcurrentHashMap<>();
            for (Map.Entry<String, Object> override : entry.getValue().entrySet()) {
                String key = normalizeKey(override.getKey());
                if (key != null && override.getValue() != null) {
                    profileOverrides.put(key, override.getValue());
                }
            }
            if (!profileOverrides.isEmpty()) {
                overridesByProfile.put(normalizeProfile(entry.getKey()), profileOverrides);
            }
        }
        if (!overridesByProfile.isEmpty()) {
            LOG.info("geometry runtime overrides restored from store profiles={}", overridesByProfile.size());
        }
    }

    private void persistReplace(String profileKey, Map<String, Object> overrides) {
        if (store == null) {
            return;
        }
        try {
            store.replaceProfileAndSave(profileKey, overrides);
        } catch (IOException e) {
            LOG.warn("geometry runtime store save failed profile={}: {}", profileKey, e.getMessage());
        }
    }

    private void persistRemove(String profileKey) {
        if (store == null) {
            return;
        }
        try {
            store.removeProfileAndSave(profileKey);
        } catch (IOException e) {
            LOG.warn("geometry runtime store remove failed profile={}: {}", profileKey, e.getMessage());
        }
    }

    private void persistClearAll() {
        if (store == null) {
            return;
        }
        try {
            store.clearAllAndSave();
        } catch (IOException e) {
            LOG.warn("geometry runtime store clear failed: {}", e.getMessage());
        }
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
