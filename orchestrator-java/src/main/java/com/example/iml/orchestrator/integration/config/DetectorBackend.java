package com.example.iml.orchestrator.integration.config;

import java.util.Locale;
import java.util.Map;

/**
 * Какой сервис анализа поверхности используется: Python FastAPI (analisSurface) или C++ (cppAnalisSurface).
 * Выбирается {@code python_detector.backend}: {@code cpp} | {@code python} (по умолчанию python — обратная совместимость).
 */
public enum DetectorBackend {
    PYTHON,
    CPP;

    public static DetectorBackend fromRootYaml(Map<String, Object> root) {
        if (root != null && root.get("python_detector") instanceof Map<?, ?> pd) {
            Object raw = pd.get("backend");
            if (raw != null && "cpp".equals(String.valueOf(raw).trim().toLowerCase(Locale.ROOT))) {
                return CPP;
            }
        }
        return PYTHON;
    }

    /** Сколько запросов инспекции одновременно принимает один сервер (python: 1; cpp: многопоточный сервер). */
    public static int serverConcurrency(Map<String, Object> pythonCfg) {
        Object raw = pythonCfg == null ? null : pythonCfg.get("server_concurrency");
        int fallback = 1;
        if (raw == null) {
            return fallback;
        }
        try {
            return Math.max(1, Math.min(64, (int) Math.round(Double.parseDouble(String.valueOf(raw).trim()))));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
