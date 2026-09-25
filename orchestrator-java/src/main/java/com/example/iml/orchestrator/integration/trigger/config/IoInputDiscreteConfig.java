package com.example.iml.orchestrator.integration.trigger.config;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Маппинг DI из {@code IoInputMonitor} (UDP): DI1=работа, DI2=направление, DI3/DI5=триггеры,
 * DI4=безопасное выключение ({@code shutdown_port}, 0 = выкл).
 */
public record IoInputDiscreteConfig(
        int workPort,
        int directionPort,
        int triggerPort,
        List<Integer> triggerPorts,
        int shutdownPort,
        int debounceMs,
        String payloadFormat,
        boolean stubWorkActive,
        TriggerEdgeMode triggerEdge,
        boolean requireDirection,
        boolean requireWork,
        boolean di3Only,
        boolean directionLatchOnWork,
        boolean directionArmNextDi3,
        boolean directionInvert,
        boolean directionLatch,
        int directionWaitMs,
        int directionPollMs,
        int captureDelayMs,
        boolean externalHardwareCapture,
        /**
         * Временно: DI2↑ → сразу wait_frame; софтовый DI3/DI5 не стартует цикл.
         * Экспозиция по-прежнему с железа (Line0); Java только ждёт кадры в окне DI2=1.
         */
        boolean armOnDirection
) {

    public List<Integer> resolveTriggerPorts() {
        if (triggerPorts != null && !triggerPorts.isEmpty()) {
            return List.copyOf(triggerPorts);
        }
        return List.of(triggerPort);
    }

    public boolean isTriggerPort(int port) {
        for (int p : resolveTriggerPorts()) {
            if (p == port) {
                return true;
            }
        }
        return false;
    }

    public String formatTriggerPorts() {
        StringBuilder sb = new StringBuilder();
        for (int p : resolveTriggerPorts()) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    public static IoInputDiscreteConfig defaults() {
        // Согласовано с config/blocks/01-core.yaml integration.inspection_trigger.io_input
        return new IoInputDiscreteConfig(
                1, 2, 3, List.of(3), 4, 0, "json", false, TriggerEdgeMode.RISING,
                true, false, false, false, false, false, true,
                5000, 1, 0, true, false
        );
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
        List<Integer> triggerPorts = parseTriggerPorts(io.get("trigger_ports"), triggerPort);
        int shutdownPort = clampOptionalDiPort(YamlScalars.toInt(io.get("shutdown_port"), defaults.shutdownPort()));
        int debounceMs = Math.max(0, YamlScalars.toInt(io.get("debounce_ms"), udpDebounceMs));
        String payloadFormat = io.get("payload_format") != null
                ? String.valueOf(io.get("payload_format")).trim().toLowerCase()
                : defaults.payloadFormat();
        boolean stubWorkActive = YamlScalars.toBool(io.get("stub_work_active"), defaults.stubWorkActive());
        boolean di3Only = YamlScalars.toBool(io.get("di3_only"), defaults.di3Only());
        boolean directionLatchOnWork = YamlScalars.toBool(
                io.get("direction_latch_on_work"),
                defaults.directionLatchOnWork()
        );
        boolean directionArmNextDi3 = YamlScalars.toBool(
                io.get("direction_arm_next_di3"),
                defaults.directionArmNextDi3()
        );
        TriggerEdgeMode triggerEdge = TriggerEdgeMode.fromConfig(io.get("trigger_edge"));
        boolean requireDirection = YamlScalars.toBool(io.get("require_direction"), defaults.requireDirection());
        boolean requireWork = directionLatchOnWork
                ? YamlScalars.toBool(io.get("require_work"), true)
                : YamlScalars.toBool(io.get("require_work"), defaults.requireWork());
        boolean directionInvert = YamlScalars.toBool(io.get("direction_invert"), defaults.directionInvert());
        boolean directionLatch = YamlScalars.toBool(io.get("direction_latch"), defaults.directionLatch());
        int directionWaitMs = Math.max(0, YamlScalars.toInt(io.get("direction_wait_ms"), defaults.directionWaitMs()));
        int directionPollMs = Math.max(1, YamlScalars.toInt(io.get("direction_poll_ms"), defaults.directionPollMs()));
        int captureDelayMs = Math.max(0, YamlScalars.toInt(io.get("capture_delay_ms"), defaults.captureDelayMs()));
        boolean externalHardwareCapture = YamlScalars.toBool(
                io.get("external_hardware_capture"),
                defaults.externalHardwareCapture()
        );
        boolean armOnDirection = YamlScalars.toBool(io.get("arm_on_direction"), defaults.armOnDirection());
        return new IoInputDiscreteConfig(
                workPort,
                directionPort,
                triggerPorts.get(0),
                triggerPorts,
                shutdownPort,
                debounceMs,
                payloadFormat,
                stubWorkActive,
                triggerEdge,
                requireDirection,
                requireWork,
                di3Only,
                directionLatchOnWork,
                directionArmNextDi3,
                directionInvert,
                directionLatch,
                directionWaitMs,
                directionPollMs,
                captureDelayMs,
                externalHardwareCapture,
                armOnDirection
        );
    }

    private static List<Integer> parseTriggerPorts(Object raw, int primary) {
        List<Integer> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                int p = clampDiPort(YamlScalars.toInt(item, 0));
                if (p >= 1 && p <= 8 && !out.contains(p)) {
                    out.add(p);
                }
            }
        }
        if (out.isEmpty()) {
            out.add(clampDiPort(primary));
        } else if (!out.contains(primary) && primary >= 1 && primary <= 8) {
            // primary первым, если задан отдельно и не в списке
            out.add(0, clampDiPort(primary));
        }
        return List.copyOf(out);
    }

    private static IoInputDiscreteConfig withDebounce(IoInputDiscreteConfig defaults, int udpDebounceMs) {
        int debounceMs = udpDebounceMs >= 0 ? udpDebounceMs : defaults.debounceMs();
        return new IoInputDiscreteConfig(
                defaults.workPort(),
                defaults.directionPort(),
                defaults.triggerPort(),
                defaults.resolveTriggerPorts(),
                defaults.shutdownPort(),
                debounceMs,
                defaults.payloadFormat(),
                defaults.stubWorkActive(),
                defaults.triggerEdge(),
                defaults.requireDirection(),
                defaults.requireWork(),
                defaults.di3Only(),
                defaults.directionLatchOnWork(),
                defaults.directionArmNextDi3(),
                defaults.directionInvert(),
                defaults.directionLatch(),
                defaults.directionWaitMs(),
                defaults.directionPollMs(),
                defaults.captureDelayMs(),
                defaults.externalHardwareCapture(),
                defaults.armOnDirection()
        );
    }

    private static int clampDiPort(int port) {
        return Math.max(1, Math.min(8, port));
    }

    /** 0 = shutdown по DI выключен; иначе DI 1–8. */
    private static int clampOptionalDiPort(int port) {
        if (port <= 0) {
            return 0;
        }
        return Math.min(8, port);
    }
}
