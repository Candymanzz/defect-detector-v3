package com.example.iml.orchestrator.integration.trigger.config;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.Map;

/** Настройки корреляции двух аппаратных импульсов DI3 в один цикл инспекции. */
public record TwoPhaseTriggerConfig(
        boolean enabled,
        int expectedDelayMs,
        int toleranceMs
) {
    public static TwoPhaseTriggerConfig defaults() {
        return new TwoPhaseTriggerConfig(false, 700, 150);
    }

    public static TwoPhaseTriggerConfig parse(Map<String, Object> inspectionTrigger) {
        TwoPhaseTriggerConfig defaults = defaults();
        if (inspectionTrigger == null) {
            return defaults;
        }
        Object raw = inspectionTrigger.get("two_phase");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return defaults;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) rawMap;
        boolean enabled = YamlScalars.toBool(config.get("enabled"), defaults.enabled());
        int expectedDelayMs = Math.max(
                0,
                YamlScalars.toInt(config.get("expected_delay_ms"), defaults.expectedDelayMs())
        );
        int toleranceMs = Math.max(
                0,
                YamlScalars.toInt(config.get("tolerance_ms"), defaults.toleranceMs())
        );
        return new TwoPhaseTriggerConfig(enabled, expectedDelayMs, toleranceMs);
    }
}
