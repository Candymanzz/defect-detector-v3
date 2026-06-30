package com.example.iml.orchestrator.integration.trigger.config;

import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.Map;

/**
 * Дискретные входы линии (Работа / Направление / Триггер).
 * Windows + Hikrobot: backend=hikrobot_mv_io → MvIOInterfaceBox.dll (JNA в оркестраторе).
 * Linux dev: backend=sysfs → /sys/class/gpio/...
 */
public record GpioTriggerConfig(
        boolean enabled,
        String backend,
        String comPort,
        int workPort,
        int directionPort,
        int triggerPort,
        boolean requireWork,
        boolean requireDirection,
        String dllDirectory,
        int pollIntervalMs,
        int debounceMs,
        int activeValue,
        GpioLineConfig work,
        GpioLineConfig direction,
        GpioLineConfig trigger
) {

    public static GpioTriggerConfig defaults() {
        return new GpioTriggerConfig(
                false,
                "hikrobot_mv_io",
                "COM2",
                1,
                2,
                3,
                true,
                true,
                "",
                2,
                100,
                1,
                GpioLineConfig.fromPath(""),
                GpioLineConfig.fromPath(""),
                GpioLineConfig.fromPath("")
        );
    }

    public boolean fullyConfigured() {
        if ("hikrobot_mv_io".equalsIgnoreCase(backend)) {
            return comPort != null && !comPort.isBlank();
        }
        return work.configured() && direction.configured() && trigger.configured();
    }

    public static GpioTriggerConfig parse(Map<String, Object> integration) {
        GpioTriggerConfig defaults = defaults();
        if (integration == null) {
            return defaults;
        }
        Object rootRaw = integration.get("inspection_trigger");
        if (!(rootRaw instanceof Map<?, ?> root)) {
            return defaults;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> triggerRoot = (Map<String, Object>) root;
        Object gpioRaw = triggerRoot.get("gpio");
        if (!(gpioRaw instanceof Map<?, ?> gpioMap)) {
            return defaults;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> gpio = (Map<String, Object>) gpioMap;
        boolean enabled = YamlScalars.toBool(gpio.get("enabled"), defaults.enabled());
        String backend = gpio.get("backend") != null
                ? String.valueOf(gpio.get("backend")).trim().toLowerCase()
                : defaults.backend();
        String comPort = gpio.get("com_port") != null
                ? String.valueOf(gpio.get("com_port")).trim()
                : defaults.comPort();
        int workPort = clampPort(YamlScalars.toInt(gpio.get("work_port"), defaults.workPort()));
        int directionPort = clampPort(YamlScalars.toInt(gpio.get("direction_port"), defaults.directionPort()));
        int triggerPort = clampPort(YamlScalars.toInt(gpio.get("trigger_port"), defaults.triggerPort()));
        String dllDirectory = gpio.get("dll_directory") != null
                ? String.valueOf(gpio.get("dll_directory")).trim()
                : defaults.dllDirectory();
        int pollIntervalMs = Math.max(1, YamlScalars.toInt(gpio.get("poll_interval_ms"), defaults.pollIntervalMs()));
        int debounceMs = Math.max(0, YamlScalars.toInt(gpio.get("debounce_ms"), defaults.debounceMs()));
        boolean requireWork = YamlScalars.toBool(gpio.get("require_work"), defaults.requireWork());
        boolean requireDirection = YamlScalars.toBool(gpio.get("require_direction"), defaults.requireDirection());
        int activeValue = GpioLineConfig.parseActiveValue(gpio.get("active_value"), defaults.activeValue());
        GpioLineConfig work = GpioLineConfig.parsePathObject(gpio.get("work"), "");
        GpioLineConfig direction = GpioLineConfig.parsePathObject(gpio.get("direction"), "");
        GpioLineConfig trigger = GpioLineConfig.parsePathObject(gpio.get("trigger"), "");
        return new GpioTriggerConfig(
                enabled,
                backend,
                comPort,
                workPort,
                directionPort,
                triggerPort,
                requireWork,
                requireDirection,
                dllDirectory,
                pollIntervalMs,
                debounceMs,
                activeValue,
                work,
                direction,
                trigger
        );
    }

    private static int clampPort(int port) {
        if (port < 1) {
            return 1;
        }
        return Math.min(port, 32);
    }
}
