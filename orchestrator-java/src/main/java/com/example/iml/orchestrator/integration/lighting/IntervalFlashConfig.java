package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;

import java.util.Map;

/**
 * Интервальный режим вспышек (отдельно от capture):
 * холостой ход (DI idle) → On, DI3↑ → On + Off через {@code off_delay_ms}.
 */
public record IntervalFlashConfig(
        boolean enabled,
        /** Порт «холостого» хода (обычно DI2). */
        int idlePort,
        /** Порт триггера съёмки (обычно DI3): On + отложенный Off. */
        int triggerPort,
        TriggerEdgeMode idleEdge,
        TriggerEdgeMode triggerEdge,
        int offDelayMs,
        /** После startupEngage гасить свет и ждать DI On. */
        boolean startDark
) {

    /** @deprecated use {@link #idlePort()} */
    public int onPort() {
        return idlePort;
    }

    /** @deprecated use {@link #triggerPort()} */
    public int offPort() {
        return triggerPort;
    }

    /** @deprecated use {@link #idleEdge()} */
    public TriggerEdgeMode onEdge() {
        return idleEdge;
    }

    /** @deprecated use {@link #triggerEdge()} */
    public TriggerEdgeMode offEdge() {
        return triggerEdge;
    }

    public static IntervalFlashConfig disabled() {
        return new IntervalFlashConfig(
                false, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 300, true
        );
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
        int idlePort = YamlScalars.toInt(
                m.get("idle_port"),
                YamlScalars.toInt(m.get("on_port"), YamlScalars.toInt(m.get("direction_port"), 2))
        );
        int triggerPort = YamlScalars.toInt(
                m.get("trigger_port"),
                YamlScalars.toInt(m.get("off_port"), 3)
        );
        // Холостой по умолчанию = DI2↓ (обратный ход). Старый on_edge сохраняем, если задан явно.
        TriggerEdgeMode idleEdge = m.containsKey("idle_edge")
                ? TriggerEdgeMode.fromConfig(m.get("idle_edge"))
                : m.containsKey("on_edge")
                        ? TriggerEdgeMode.fromConfig(m.get("on_edge"))
                        : TriggerEdgeMode.FALLING;
        TriggerEdgeMode triggerEdge = m.containsKey("trigger_edge")
                ? TriggerEdgeMode.fromConfig(m.get("trigger_edge"))
                : m.containsKey("off_edge")
                        ? TriggerEdgeMode.fromConfig(m.get("off_edge"))
                        : TriggerEdgeMode.RISING;
        int offDelayMs = Math.max(0, YamlScalars.toInt(m.get("off_delay_ms"), 300));
        boolean startDark = YamlScalars.toBool(m.get("start_dark"), true);
        return new IntervalFlashConfig(
                enabled, idlePort, triggerPort, idleEdge, triggerEdge, offDelayMs, startDark
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        if (root == null) {
            return null;
        }
        Object raw = root.get(key);
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
