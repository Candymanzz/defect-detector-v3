package com.example.iml.orchestrator.integration.config;

import java.util.Map;

/**
 * Детектор analisSurface: только HTTP FastAPI ({@code python_detector.base_url}).
 */
public record PythonDetectorConfig(String baseUrl) {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8000";

    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    public static PythonDetectorConfig fromRootYaml(Map<String, Object> root) {
        if (root == null) {
            return new PythonDetectorConfig(DEFAULT_BASE_URL);
        }
        String url = null;
        Object pd = root.get("python_detector");
        if (pd instanceof Map<?, ?> pdm) {
            Object u = pdm.get("base_url");
            if (u != null) {
                url = String.valueOf(u).trim();
            }
        }
        if (url == null || url.isBlank()) {
            Object ca = root.get("client_api");
            if (ca instanceof Map<?, ?> cam) {
                Object u = cam.get("kopcheni_base_url");
                if (u != null) {
                    url = String.valueOf(u).trim();
                }
            }
        }
        if (url == null || url.isBlank()) {
            url = DEFAULT_BASE_URL;
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return new PythonDetectorConfig(url);
    }
}
