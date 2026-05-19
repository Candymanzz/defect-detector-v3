package com.example.iml.orchestrator.integration.config;

/**
 * Источник эталона для пайплайна инспекции.
 */
public enum ReferenceSource {
    /** Захват эталона с камеры при старте / reload. */
    CAMERA,
    /** Только {@code client.reference_bundle} по WebSocket. */
    CLIENT;

    public static ReferenceSource fromConfig(Object raw) {
        if (raw == null) {
            return CAMERA;
        }
        String s = String.valueOf(raw).trim().toLowerCase();
        return switch (s) {
            case "client", "ws", "frontend" -> CLIENT;
            default -> CAMERA;
        };
    }
}
