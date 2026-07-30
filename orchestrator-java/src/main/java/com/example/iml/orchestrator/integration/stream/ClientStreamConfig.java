package com.example.iml.orchestrator.integration.stream;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.Map;

/** Настройки клиентского видеопотока ({@code integration.client_stream}). */
public record ClientStreamConfig(int defaultMaxFps, int maxFpsCap) {

    private static final int DEFAULT_FPS = 20;
    private static final int DEFAULT_CAP = 30;

    public static ClientStreamConfig fromRootYaml(Map<String, Object> root) {
        if (root == null) {
            return defaults();
        }
        Object integrationRaw = root.get("integration");
        if (!(integrationRaw instanceof Map<?, ?> integration)) {
            return defaults();
        }
        Object raw = integration.get("client_stream");
        if (!(raw instanceof Map<?, ?> m)) {
            return defaults();
        }
        int fps = Math.max(1, YamlScalars.toInt(m.get("default_max_fps"), DEFAULT_FPS));
        int cap = Math.max(fps, YamlScalars.toInt(m.get("max_fps_cap"), DEFAULT_CAP));
        return new ClientStreamConfig(fps, Math.min(30, cap));
    }

    public static ClientStreamConfig defaults() {
        return new ClientStreamConfig(DEFAULT_FPS, DEFAULT_CAP);
    }

    public int clampFps(int requested) {
        int fps = requested > 0 ? requested : defaultMaxFps;
        return Math.max(1, Math.min(maxFpsCap, fps));
    }
}
