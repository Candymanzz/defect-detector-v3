package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime ROI и пороги: {@code inspect_shm} java-geometry и {@code threshold} python-детектора.
 */
public final class GeometryRuntimeConfig {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> overridesByProfile = new ConcurrentHashMap<>();

    public void clear() {
        overridesByProfile.clear();
    }

    public void clear(String analysisProfile) {
        overridesByProfile.remove(GeometryRuntimeKeys.normalizeProfile(analysisProfile));
    }

    /**
     * Полная замена набора переопределений (ключи как в заголовке geometry: mainRoi, maxShiftMm, …
     * или snake_case: main_roi, max_shift_mm — нормализуются).
     */
    public void replaceAllFromClient(Map<String, Object> body) {
        replaceAllFromClient(null, body);
    }

    public void replaceAllFromClient(String analysisProfile, Map<String, Object> body) {
        String profileKey = GeometryRuntimeKeys.normalizeProfile(analysisProfile);
        ConcurrentHashMap<String, Object> profileOverrides = new ConcurrentHashMap<>();
        if (body == null) {
            overridesByProfile.put(profileKey, profileOverrides);
            return;
        }
        for (Map.Entry<String, Object> e : body.entrySet()) {
            String key = GeometryRuntimeKeys.normalizeKey(e.getKey());
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
                overridesByProfile.computeIfAbsent(
                        GeometryRuntimeKeys.normalizeProfile(analysisProfile),
                        ignored -> new ConcurrentHashMap<>()
                );
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String key = GeometryRuntimeKeys.normalizeKey(entry.getKey());
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
            GeometryRuntimeKeys.applyGeometryEntry(header, key, value);
        });
    }

    /** Порог чувствительности для python {@code inspect_shm} (поверх {@code python_detector.fallback_threshold}). */
    public void applyToPythonHeader(Map<String, Object> header, Map<String, Object> pythonYaml) {
        applyToPythonHeader(header, pythonYaml, null);
    }

    public void applyToPythonHeader(Map<String, Object> header, Map<String, Object> pythonYaml, String analysisProfile) {
        GeometryRuntimeDisplay.applyToPythonHeader(header, pythonYaml, profileOverrides(analysisProfile));
    }

    public double resolvePythonThreshold(Map<String, Object> pythonYaml) {
        return resolvePythonThreshold(pythonYaml, null);
    }

    public double resolvePythonThreshold(Map<String, Object> pythonYaml, String analysisProfile) {
        Map<String, Object> overrides = profileOverrides(analysisProfile);
        if (overrides.containsKey("threshold")) {
            return YamlScalars.toDouble(
                    overrides.get("threshold"),
                    GeometryRuntimeKeys.defaultPythonThreshold(pythonYaml)
            );
        }
        return GeometryRuntimeKeys.defaultPythonThreshold(pythonYaml);
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
        return GeometryRuntimeDisplay.effectiveForDisplay(
                yamlGeometry,
                pythonYaml,
                this::applyToGeometryHeader,
                analysisProfile
        );
    }

    private Map<String, Object> profileOverrides(String analysisProfile) {
        return overridesByProfile.getOrDefault(
                GeometryRuntimeKeys.normalizeProfile(analysisProfile),
                new ConcurrentHashMap<>()
        );
    }
}
