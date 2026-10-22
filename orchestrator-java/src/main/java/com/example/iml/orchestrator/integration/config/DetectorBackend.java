package com.example.iml.orchestrator.integration.config;

import java.util.Map;

/** C++ detector selection. The YAML section keeps its historical name for existing configs. */
public enum DetectorBackend {
    CPP;

    public static DetectorBackend fromRootYaml(Map<String, Object> root) {
        return CPP;
    }

    /** Сколько запросов инспекции одновременно принимает C++-сервер. */
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
