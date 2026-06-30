package com.example.iml.orchestrator.integration.trigger.gpio;

/** Фронт на DI-триггере. */
public enum TriggerEdge {
    RISING,
    FALLING,
    BOTH;

    public static TriggerEdge parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return RISING;
        }
        return switch (raw.trim().toLowerCase()) {
            case "falling", "fall", "1to0", "1->0" -> FALLING;
            case "both", "any" -> BOTH;
            default -> RISING;
        };
    }
}
