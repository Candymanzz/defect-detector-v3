package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import java.util.Map;
import java.util.Objects;

/** Deployment-dependent light client timing and persistence settings. */
public record LightRuntimeConfig(int commandAttempts, int retryDelayMs, int startupPollMs,
                                 int statusTimeoutMs, String brightnessStorePath) {
    public static LightRuntimeConfig defaults() { return parse(Map.of()); }
    public static LightRuntimeConfig parse(Map<String, Object> root) {
        Map<?, ?> light = root.get("light_servers") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> runtime = light.get("runtime") instanceof Map<?, ?> m ? m : Map.of();
        int attempts = value(runtime, "command_attempts", 10, 1);
        int delay = value(runtime, "retry_delay_ms", 200, 0);
        int poll = value(runtime, "startup_poll_ms", 400, 1);
        int timeout = value(runtime, "status_timeout_ms", 3000, 1);
        String path = Objects.toString(light.get("brightness_store_path"), "config/data/light_brightness_settings.json").trim();
        if (path.isBlank()) throw new IllegalArgumentException("light_servers.brightness_store_path must not be empty");
        return new LightRuntimeConfig(attempts, delay, poll, timeout, path);
    }
    private static int value(Map<?, ?> map, String key, int fallback, int min) {
        int value = YamlScalars.toInt(map.get(key), fallback);
        if (value < min) throw new IllegalArgumentException("light_servers.runtime." + key + " must be >= " + min);
        return value;
    }
}
