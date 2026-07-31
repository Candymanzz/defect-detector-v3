package com.example.iml.orchestrator.integration.trigger.config;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;

import java.util.Map;

/**
 * Маппинг DI из {@code IoInputMonitor} (UDP): DI1=работа, DI2=направление, DI3=триггер, DI4=БП.
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
        boolean shutdownPrepOnWorkHigh,
        int shutdownPrepPort
    ) {

    public static IoInputDiscreteConfig defaults() {
        // Согласовано с config/blocks/01-core.yaml integration.inspection_trigger.io_input
        return new IoInputDiscreteConfig(
                1, 2, 3, 0, "json", false, TriggerEdgeMode.RISING,
                true, false, false, false, false, false, true,
                5000, 1, 0, true, true, 4
        );
    }

    public static IoInputDiscreteConfig parse(Map<String, Object> integration, int udpDebounceMs) {
        IoInputDiscreteConfig defaults = defaults();
        if (integration == null) {
            return withDebounce(defaults, udpDebounceMs);
        }
        Object rootRaw = integration.get("inspection_trigger");
        if (!(rootRaw instanceof Map<?, ?> triggerRoot)) {
            return withDebounce(defaults, udpDebounceMs);
        }
        Object ioRaw = triggerRoot.get("io_input");
        if (!(ioRaw instanceof Map<?, ?> io)) {
            return withDebounce(defaults, udpDebounceMs);
        }
        int workPort = clampDiPort(YamlScalars.toInt(io.get("work_port"), defaults.workPort()));
        int directionPort = clampDiPort(YamlScalars.toInt(io.get("direction_port"), defaults.directionPort()));
        int triggerPort = clampDiPort(YamlScalars.toInt(io.get("trigger_port"), defaults.triggerPort()));
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
        boolean shutdownPrepOnWorkHigh = YamlScalars.toBool(
                io.get("shutdown_prep_on_work_high"),
                defaults.shutdownPrepOnWorkHigh()
        );
        int shutdownPrepPort = clampDiPort(YamlScalars.toInt(
                io.get("shutdown_prep_port"),
                defaults.shutdownPrepPort()
        ));
        return new IoInputDiscreteConfig(
                workPort,
                directionPort,
                triggerPort,
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
                shutdownPrepOnWorkHigh,
                shutdownPrepPort
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
                defaults.shutdownPrepOnWorkHigh(),
                defaults.shutdownPrepPort()
        );
    }

    private static int clampDiPort(int port) {
        return Math.max(1, Math.min(8, port));
    }
}
