package com.example.iml.orchestrator.integration.trigger.config;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;

import java.util.Map;

/**
 * Маппинг DI из {@code IoInputMonitor} (UDP): DI1=работа, DI2=направление, DI3=триггер.
 */
public record IoInputDiscreteConfig(
        int workPort,
        int directionPort,
        int triggerPort,
        int debounceMs,
        String payloadFormat,
        boolean stubWorkActive,
        TriggerEdgeMode triggerEdge,
        boolean requireDirection,
        boolean directionInvert,
        int directionWaitMs,
        int directionPollMs
) {

    public static IoInputDiscreteConfig defaults() {
        return new IoInputDiscreteConfig(1, 2, 3, 100, "json", false, TriggerEdgeMode.RISING, true, false, 5000, 50);
    }

    public static IoInputDiscreteConfig parse(Map<String, Object> integration, int udpDebounceMs) {
        IoInputDiscreteConfig defaults = defaults();
        if (integration == null) {
            return withDebounce(defaults, udpDebounceMs);
        }
        Object rootRaw = integration.get("inspection_trigger");
        if (!(rootRaw instanceof Map<?, ?> root)) {
            return withDebounce(defaults, udpDebounceMs);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> triggerRoot = (Map<String, Object>) root;
        Object ioRaw = triggerRoot.get("io_input");
        if (!(ioRaw instanceof Map<?, ?> ioMap)) {
            return withDebounce(defaults, udpDebounceMs);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> io = (Map<String, Object>) ioMap;
        int workPort = clampDiPort(YamlScalars.toInt(io.get("work_port"), defaults.workPort()));
        int directionPort = clampDiPort(YamlScalars.toInt(io.get("direction_port"), defaults.directionPort()));
        int triggerPort = clampDiPort(YamlScalars.toInt(io.get("trigger_port"), defaults.triggerPort()));
        int debounceMs = Math.max(0, YamlScalars.toInt(io.get("debounce_ms"), udpDebounceMs));
        String payloadFormat = io.get("payload_format") != null
                ? String.valueOf(io.get("payload_format")).trim().toLowerCase()
                : defaults.payloadFormat();
        boolean stubWorkActive = YamlScalars.toBool(io.get("stub_work_active"), defaults.stubWorkActive());
        TriggerEdgeMode triggerEdge = TriggerEdgeMode.fromConfig(io.get("trigger_edge"));
        boolean requireDirection = YamlScalars.toBool(io.get("require_direction"), defaults.requireDirection());
        boolean directionInvert = YamlScalars.toBool(io.get("direction_invert"), defaults.directionInvert());
        int directionWaitMs = Math.max(0, YamlScalars.toInt(io.get("direction_wait_ms"), defaults.directionWaitMs()));
        int directionPollMs = Math.max(1, YamlScalars.toInt(io.get("direction_poll_ms"), defaults.directionPollMs()));
        return new IoInputDiscreteConfig(
                workPort,
                directionPort,
                triggerPort,
                debounceMs,
                payloadFormat,
                stubWorkActive,
                triggerEdge,
                requireDirection,
                directionInvert,
                directionWaitMs,
                directionPollMs
        );
    }

    private static IoInputDiscreteConfig withDebounce(IoInputDiscreteConfig defaults, int udpDebounceMs) {
        int debounceMs = udpDebounceMs >= 0 ? udpDebounceMs : defaults.debounceMs();
        return new IoInputDiscreteConfig(
                defaults.workPort(),
                defaults.directionPort(),
                defaults.triggerPort(),
                debounceMs,
                defaults.payloadFormat(),
                defaults.stubWorkActive(),
                defaults.triggerEdge(),
                defaults.requireDirection(),
                defaults.directionInvert(),
                defaults.directionWaitMs(),
                defaults.directionPollMs()
        );
    }

    private static int clampDiPort(int port) {
        return Math.max(1, Math.min(8, port));
    }
}
