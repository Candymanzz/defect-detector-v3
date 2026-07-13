package com.example.iml.orchestrator.integration.lighting;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Результат разбора запроса на изменение яркости (глобально и/или по endpoint id). */
public record LightBrightnessUpdate(
        Integer globalPercent,
        Map<String, Integer> perEndpoint
) {

    public LightBrightnessUpdate {
        perEndpoint = perEndpoint == null ? Map.of() : Map.copyOf(perEndpoint);
    }

    public static LightBrightnessUpdate globalOnly(int percent) {
        return new LightBrightnessUpdate(percent, Map.of());
    }

    public static LightBrightnessUpdate empty() {
        return new LightBrightnessUpdate(null, Map.of());
    }

    public boolean isEmpty() {
        return globalPercent == null && perEndpoint.isEmpty();
    }

    public static LightBrightnessApplyResult apply(LightTriggerClient client, LightBrightnessUpdate update) {
        if (client == null || update == null || update.isEmpty()) {
            return LightBrightnessApplyResult.none();
        }
        return client.applyBrightnessUpdate(update);
    }
}
