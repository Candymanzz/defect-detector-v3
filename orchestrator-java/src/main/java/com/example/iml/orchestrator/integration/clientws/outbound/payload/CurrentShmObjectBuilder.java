package com.example.iml.orchestrator.integration.clientws.outbound.payload;

import com.example.iml.orchestrator.integration.clientws.exception.ClientWsInvalidCaptureDescriptorException;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundJson;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Builds the {@code current} SHM descriptor object used by preview / inspect payloads.
 */
public final class CurrentShmObjectBuilder {

    private CurrentShmObjectBuilder() {
    }

    public static ObjectNode buildCurrentShmObjectNode(
            int cameraId,
            Map<String, Object> header,
            long frameIdLong,
            String shmName
    ) throws ClientWsInvalidCaptureDescriptorException {
        int width = YamlScalars.toInt(header.get("width"), 0);
        int height = YamlScalars.toInt(header.get("height"), 0);
        if (width <= 0 || height <= 0) {
            throw new ClientWsInvalidCaptureDescriptorException("width/height");
        }
        String pixelFormat = "bgr_u8";
        Object pf = header.get("pixel_format");
        if (pf != null) {
            String pfs = String.valueOf(pf).trim();
            if (!pfs.isEmpty()) {
                pixelFormat = pfs;
            }
        }
        int channels = YamlScalars.toInt(header.get("channels"), 0);
        if (channels <= 0) {
            channels = "gray_u8".equalsIgnoreCase(pixelFormat) ? 1 : 3;
        }
        int stride = YamlScalars.toInt(header.get("stride"), 0);
        if (stride <= 0) {
            stride = width * channels;
        }
        if (stride < width * channels) {
            throw new ClientWsInvalidCaptureDescriptorException("stride");
        }
        long shmOffsetLong = YamlScalars.toLong(header.get("shm_offset"), 0L);
        if (shmOffsetLong < 0 || shmOffsetLong > Integer.MAX_VALUE) {
            throw new ClientWsInvalidCaptureDescriptorException("shm_offset");
        }
        int shmOffset = (int) shmOffsetLong;
        ObjectNode current = WsOutboundJson.JSON.createObjectNode();
        current.put("camera_id", cameraId);
        current.put("frame_id", Long.toString(frameIdLong));
        current.put("shm_name", shmName);
        current.put("width", width);
        current.put("height", height);
        current.put("stride", stride);
        current.put("shm_offset", shmOffset);
        current.put("pixel_format", pixelFormat);
        current.put("channels", channels);
        Object exp = header.get("expires_at_ms");
        if (exp instanceof Number n) {
            current.put("expires_at_ms", n.longValue());
        }
        Object ttl = header.get("ttl_ms");
        if (ttl instanceof Number n) {
            current.put("ttl_ms", n.intValue());
        }
        Object rt = header.get("read_token");
        if (rt != null) {
            String tok = String.valueOf(rt).trim();
            if (!tok.isEmpty()) {
                current.put("read_token", tok);
            }
        }
        return current;
    }
}
