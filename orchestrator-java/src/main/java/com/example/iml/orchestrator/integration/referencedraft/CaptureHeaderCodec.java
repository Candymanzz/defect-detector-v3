package com.example.iml.orchestrator.integration.referencedraft;

import com.example.iml.orchestrator.integration.clientws.bundle.ShmFrameRefData;
import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Копирование заголовка capture и сбор {@link ShmFrameRefData} для слота камеры {@code 0…4}.
 */
public final class CaptureHeaderCodec {

    private CaptureHeaderCodec() {
    }

    public static Map<String, Object> shallowCopy(Map<String, Object> header) {
        return new LinkedHashMap<>(header);
    }

    /**
     * @param slotCameraId индекс слота 0…4 (в эталонном пакете он же {@code camera_id} у кадра)
     */
    public static ShmFrameRefData toShmRef(int slotCameraId, Map<String, Object> h) {
        if (h == null) {
            throw new IllegalArgumentException("header null");
        }
        String frameId = String.valueOf(h.getOrDefault("frame_id", "")).trim();
        if (frameId.isEmpty()) {
            throw new IllegalArgumentException("frame_id required");
        }
        String shmName = String.valueOf(h.getOrDefault("shm_name", "")).trim();
        if (shmName.isEmpty()) {
            throw new IllegalArgumentException("shm_name required");
        }
        int width = YamlScalars.toInt(h.get("width"), 0);
        int height = YamlScalars.toInt(h.get("height"), 0);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width/height invalid");
        }
        int channels = YamlScalars.toInt(h.get("channels"), 0);
        String pixelFormat = "bgr_u8";
        Object pf = h.get("pixel_format");
        if (pf != null) {
            String pfs = String.valueOf(pf).trim();
            if (!pfs.isEmpty()) {
                pixelFormat = pfs;
            }
        }
        if (channels <= 0) {
            channels = "gray_u8".equalsIgnoreCase(pixelFormat) ? 1 : 3;
        }
        int stride = YamlScalars.toInt(h.get("stride"), 0);
        if (stride <= 0) {
            stride = width * channels;
        }
        if (stride < width * channels) {
            throw new IllegalArgumentException("stride too small");
        }
        long shmOffsetLong = YamlScalars.toLong(h.get("shm_offset"), 0L);
        if (shmOffsetLong < 0 || shmOffsetLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("shm_offset invalid");
        }
        int shmOffset = (int) shmOffsetLong;
        Long expiresAt = null;
        Object exp = h.get("expires_at_ms");
        if (exp instanceof Number n) {
            expiresAt = n.longValue();
        }
        Integer ttl = null;
        Object ttlObj = h.get("ttl_ms");
        if (ttlObj instanceof Number n) {
            ttl = n.intValue();
        }
        String readToken = null;
        Object rt = h.get("read_token");
        if (rt != null) {
            String tok = String.valueOf(rt).trim();
            if (!tok.isEmpty()) {
                readToken = tok;
            }
        }
        return new ShmFrameRefData(
                slotCameraId,
                frameId,
                shmName,
                width,
                height,
                stride,
                shmOffset,
                pixelFormat,
                channels,
                expiresAt,
                ttl,
                readToken
        );
    }
}
