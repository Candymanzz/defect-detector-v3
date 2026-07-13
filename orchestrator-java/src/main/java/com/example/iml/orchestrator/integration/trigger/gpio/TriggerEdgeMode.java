package com.example.iml.orchestrator.integration.trigger.gpio;

/**
 * Фронт DI-триггера: замыкание (0→1) или размыкание (1→0).
 */
public enum TriggerEdgeMode {
    RISING,
    FALLING;

    public static TriggerEdgeMode fromConfig(Object raw) {
        if (raw == null) {
            return RISING;
        }
        return switch (String.valueOf(raw).trim().toLowerCase()) {
            case "falling", "open", "release" -> FALLING;
            default -> RISING;
        };
    }
}
