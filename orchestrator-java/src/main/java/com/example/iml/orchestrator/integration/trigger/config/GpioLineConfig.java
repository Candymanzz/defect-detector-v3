package com.example.iml.orchestrator.integration.trigger.config;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.Map;

/** Одна дискретная линия GPIO (sysfs value-файл). */
public record GpioLineConfig(String path) {

    public static GpioLineConfig parse(Map<String, Object> raw) {
        if (raw == null) {
            return new GpioLineConfig("");
        }
        String path = raw.get("path") != null ? String.valueOf(raw.get("path")).trim() : "";
        return new GpioLineConfig(path);
    }

    public boolean configured() {
        return path != null && !path.isBlank();
    }

    public static GpioLineConfig fromPath(String path) {
        return new GpioLineConfig(path == null ? "" : path.trim());
    }

    public static GpioLineConfig parsePathObject(Object raw, String defaultPath) {
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            GpioLineConfig parsed = parse((Map<String, Object>) map);
            if (parsed.configured()) {
                return parsed;
            }
        } else if (raw != null) {
            String text = String.valueOf(raw).trim();
            if (!text.isEmpty()) {
                return new GpioLineConfig(text);
            }
        }
        return new GpioLineConfig(defaultPath == null ? "" : defaultPath);
    }

    public static int parseActiveValue(Object raw, int defaultValue) {
        return YamlScalars.toInt(raw, defaultValue) != 0 ? 1 : 0;
    }
}
