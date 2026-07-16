package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;

import java.util.Map;

/**
 * Интервальный режим вспышек (отдельно от capture):
 * DI «направление» → On, DI «триггер» → Off (опционально с задержкой).
 */
public record IntervalFlashConfig(
        boolean enabled,
        int onPort,
        int offPort,
        TriggerEdgeMode onEdge,
        TriggerEdgeMode offEdge,
        int offDelayMs,
        /** После startupEngage гасить свет и ждать DI On. */
        boolean startDark
) {

    public static IntervalFlashConfig disabled() {
        return new IntervalFlashConfig(false, 2, 3, TriggerEdgeMode.RISING, TriggerEdgeMode.RISING, 0, true);
    }

    @SuppressWarnings("unchecked")
    public static IntervalFlashConfig fromRootYaml(Map<String, Object> root) {
        Map<String, Object> ls = section(root, "light_servers");
        if (ls == null) {
            return disabled();
        }
        Object raw = ls.get("interval_flash");
        if (!(raw instanceof Map<?, ?>)) {
            return disabled();
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        boolean enabled = YamlScalars.toBool(m.get("enabled"), false);
        int onPort = YamlScalars.toInt(m.get("on_port"), YamlScalars.toInt(m.get("direction_port"), 2));
        int offPort = YamlScalars.toInt(m.get("off_port"), YamlScalars.toInt(m.get("trigger_port"), 3));
        TriggerEdgeMode onEdge = TriggerEdgeMode.fromConfig(m.get("on_edge"));
        TriggerEdgeMode offEdge = TriggerEdgeMode.fromConfig(m.get("off_edge"));
        int offDelayMs = Math.max(0, YamlScalars.toInt(m.get("off_delay_ms"), 0));
        boolean startDark = YamlScalars.toBool(m.get("start_dark"), true);
        return new IntervalFlashConfig(enabled, onPort, offPort, onEdge, offEdge, offDelayMs, startDark);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        if (root == null) {
            return null;
        }
        Object raw = root.get(key);
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
