package com.example.iml.orchestrator.integration.clientws.bundle;

/**
 * Ссылка на кадр в SHM (без пикселей), см. контракт Фазы 0.
 */
public record ShmFrameRefData(
        int cameraId,
        String frameId,
        String shmName,
        int width,
        int height,
        int strideBytes,
        int shmOffset,
        String pixelFormat,
        int channels,
        Long expiresAtMs,
        Integer ttlMs,
        String readToken
) {
}
