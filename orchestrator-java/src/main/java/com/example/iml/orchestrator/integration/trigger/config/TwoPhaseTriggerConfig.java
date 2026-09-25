package com.example.iml.orchestrator.integration.trigger.config;



import com.example.iml.orchestrator.integration.config.YamlScalars;



import java.util.Map;



/** Двухфазная съёмка: два DI3 (legacy) или один DI3 + burst phase1 (single_di3_burst). */

public record TwoPhaseTriggerConfig(

        boolean enabled,

        int expectedDelayMs,

        int toleranceMs,

        /** Пауза до 2-й фазы: burst (single DI3) или fallback без 2-го DI3 (legacy). */

        int autoSecondPhaseDelayMs,

        /** Legacy: мин. пауза перед software phase1 (мс). */

        int fallbackPhysicalGraceMs,

        /** Один DI3 при DI2=1 → phase0 сразу, phase1 через auto_second_phase_delay_ms. */

        boolean singleDi3Burst,

        String ioControlHttpHost,

        int ioControlHttpPort

) {

    public static TwoPhaseTriggerConfig defaults() {

        return new TwoPhaseTriggerConfig(false, 700, 150, 0, 75, false, "127.0.0.1", 9101);

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

        int autoSecondPhaseDelayMs;

        if (config.containsKey("auto_second_phase_delay_ms")) {

            autoSecondPhaseDelayMs = Math.max(

                    0,

                    YamlScalars.toInt(config.get("auto_second_phase_delay_ms"), 0)

            );

        } else if (YamlScalars.toBool(config.get("single_di3_burst"), false)) {

            autoSecondPhaseDelayMs = Math.max(0, YamlScalars.toInt(config.get("burst_delay_ms"), 76));

        } else {

            autoSecondPhaseDelayMs = enabled ? 80 : 0;

        }

        int fallbackPhysicalGraceMs = Math.max(

                0,

                YamlScalars.toInt(config.get("fallback_physical_grace_ms"), defaults.fallbackPhysicalGraceMs())

        );

        boolean singleDi3Burst = YamlScalars.toBool(config.get("single_di3_burst"), defaults.singleDi3Burst());

        String ioControlHttpHost = config.get("io_control_http_host") != null

                ? String.valueOf(config.get("io_control_http_host")).trim()

                : defaults.ioControlHttpHost();

        int ioControlHttpPort = Math.max(

                1,

                Math.min(

                        65535,

                        YamlScalars.toInt(config.get("io_control_http_port"), defaults.ioControlHttpPort())

                )

        );

        return new TwoPhaseTriggerConfig(

                enabled,

                expectedDelayMs,

                toleranceMs,

                autoSecondPhaseDelayMs,

                fallbackPhysicalGraceMs,

                singleDi3Burst,

                ioControlHttpHost,

                ioControlHttpPort

        );

    }

}


