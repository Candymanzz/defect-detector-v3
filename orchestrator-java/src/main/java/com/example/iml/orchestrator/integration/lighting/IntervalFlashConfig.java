package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.config.YamlMaps;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;

import java.util.Map;

/**
 * Интервальный режим вспышек (отдельно от capture):
 * DI3↑ → On + Off через {@code off_delay_ms} (или раньше по первому кадру);
 * после Off — авто-On через {@code on_reengage_delay_ms};
 * опционально холостой DI → On.
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
        /** Авто-On через N мс после Off; 0 = только по DI / idle. */
        int onReengageDelayMs,
        /** После startupEngage гасить свет и ждать DI On. */
        boolean startDark,
        /**
         * Включать банк на холостом DI (DI2). По умолчанию false:
         * иначе пресвет на обратном ходе → «выжиг» следующего кадра.
         */
        boolean idleOnEnabled,
        /**
         * Гасить банк на первом usable wait_frame после DI3;
         * {@code off_delay_ms} остаётся аварийным timeout.
         */
        boolean offOnFirstFrame
) {

    public static IntervalFlashConfig disabled() {
        return new IntervalFlashConfig(
                false, 2, 3, TriggerEdgeMode.FALLING, TriggerEdgeMode.RISING, 300, 0, true, false, false
        );
    }

    public static IntervalFlashConfig fromRootYaml(Map<String, Object> root) {
        Map<String, Object> ls = YamlMaps.stringObjectMapOrNull(root == null ? null : root.get("light_servers"));
        if (ls == null) {
            return disabled();
        }
        Object raw = ls.get("interval_flash");
        if (!(raw instanceof Map<?, ?> m)) {
            return disabled();
        }
        boolean enabled = YamlScalars.toBool(m.get("enabled"), false);
        int idlePort = YamlScalars.toInt(
                m.get("idle_port"),
                YamlScalars.toInt(m.get("on_port"), YamlScalars.toInt(m.get("direction_port"), 2))
        );
        int triggerPort = YamlScalars.toInt(
                m.get("trigger_port"),
                YamlScalars.toInt(m.get("off_port"), 3)
        );
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
        int offDelayMs = Math.max(0, YamlScalars.toInt(m.get("off_delay_ms"), 1000));
        int onReengageDelayMs = Math.max(0, YamlScalars.toInt(m.get("on_reengage_delay_ms"), 5000));
        boolean startDark = YamlScalars.toBool(m.get("start_dark"), true);
        boolean idleOnEnabled = YamlScalars.toBool(m.get("idle_on"), false);
        boolean offOnFirstFrame = YamlScalars.toBool(m.get("off_on_first_frame"), true);
        return new IntervalFlashConfig(
                enabled,
                idlePort,
                triggerPort,
                idleEdge,
                triggerEdge,
                offDelayMs,
                onReengageDelayMs,
                startDark,
                idleOnEnabled,
                offOnFirstFrame
        );
    }
}
