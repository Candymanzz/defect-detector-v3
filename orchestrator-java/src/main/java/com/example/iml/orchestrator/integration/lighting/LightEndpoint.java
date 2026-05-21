package com.example.iml.orchestrator.integration.lighting;

/** Один HTTP-эндпоинт подсветки LightServer.v3 (COM IO или MV-LE). */
public interface LightEndpoint {

    String id();

    boolean enabled();

    void ensureReady();

    void trigger(int cameraId, long frameId, String phase, int brightnessPercent, int durationMs) throws Exception;

    /** Принудительно погасить все каналы (при остановке приложения). */
    void turnOffAll() throws Exception;
}
