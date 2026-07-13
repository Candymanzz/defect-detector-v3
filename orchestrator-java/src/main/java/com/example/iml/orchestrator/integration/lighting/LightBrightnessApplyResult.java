package com.example.iml.orchestrator.integration.lighting;

import java.util.ArrayList;
import java.util.List;

/** Результат применения яркости: значение в памяти оркестратора и ошибки push в LightServer. */
public record LightBrightnessApplyResult(List<String> hardwareErrors) {

    public LightBrightnessApplyResult {
        hardwareErrors = hardwareErrors == null ? List.of() : List.copyOf(hardwareErrors);
    }

    public static LightBrightnessApplyResult none() {
        return new LightBrightnessApplyResult(List.of());
    }

    public static LightBrightnessApplyResult disabled() {
        return new LightBrightnessApplyResult(List.of("light_servers disabled"));
    }

    public boolean hasHardwareErrors() {
        return !hardwareErrors.isEmpty();
    }

    public static LightBrightnessApplyResult merge(LightBrightnessApplyResult a, LightBrightnessApplyResult b) {
        if (a.hardwareErrors.isEmpty()) {
            return b;
        }
        if (b.hardwareErrors.isEmpty()) {
            return a;
        }
        List<String> merged = new ArrayList<>(a.hardwareErrors.size() + b.hardwareErrors.size());
        merged.addAll(a.hardwareErrors);
        merged.addAll(b.hardwareErrors);
        return new LightBrightnessApplyResult(merged);
    }
}
