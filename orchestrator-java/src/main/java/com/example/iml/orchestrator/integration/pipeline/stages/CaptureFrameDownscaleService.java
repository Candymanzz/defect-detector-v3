package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Downscale capture/reference frames into dedicated SHM files and return remapped headers.
 */
public final class CaptureFrameDownscaleService {

    private final Logger log;
    private final double scale;

    public CaptureFrameDownscaleService(Logger log, double scale) {
        this.log = log;
        this.scale = ShmBgrDownscale.sanitizeScale(scale);
    }

    public BinaryProtocol.Message downscaleCapture(BinaryProtocol.Message capture, int cameraId, String sourceTag) {
        if (capture == null || capture.header() == null || scale >= 0.999d) {
            return capture;
        }
        Map<String, Object> src = capture.header();
        String shmName = String.valueOf(src.getOrDefault("shm_name", "")).trim();
        int width = YamlScalars.toInt(src.get("width"), 0);
        int height = YamlScalars.toInt(src.get("height"), 0);
        int stride = YamlScalars.toInt(src.get("stride"), 0);
        long shmOffset = YamlScalars.toLong(src.get("shm_offset"), 0L);
        long frameId = YamlScalars.toLong(src.get("frame_id"), -1L);
        if (shmName.isBlank() || width <= 0 || height <= 0 || stride < width * 3 || shmOffset < 0L) {
            return capture;
        }
        try {
            int resolvedCameraId = YamlScalars.toInt(src.get("camera_id"), cameraId);
            Path sourcePath = FrameJpegWriter.resolveShmPath(shmName, resolvedCameraId);
            if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
                throw new IOException("source shm not found: " + shmName);
            }
            byte[] bgr = ShmBgrDownscale.readBgr(sourcePath, shmOffset, width, height, stride);
            int outW = Math.max(1, (int) Math.round(width * scale));
            int outH = Math.max(1, (int) Math.round(height * scale));
            byte[] outBgr = ShmBgrDownscale.resizeBgr(bgr, width, height, outW, outH);
            String outName = "iml_ds_" + sourceTag + "_cam" + resolvedCameraId;
            Path outPath = FrameJpegWriter.imlShmFilePath(outName);
            ShmBgrDownscale.writeBgrFile(outPath, outBgr);
            Map<String, Object> downscaled = new LinkedHashMap<>(src);
            downscaled.put("shm_name", "/" + outName);
            downscaled.put("shm_offset", 0L);
            downscaled.put("width", outW);
            downscaled.put("height", outH);
            downscaled.put("stride", outW * 3);
            downscaled.put("pixel_format", "bgr_u8");
            downscaled.put("channels", 3);
            downscaled.put("downscaled_from_width", width);
            downscaled.put("downscaled_from_height", height);
            downscaled.put("downscale_scale", scale);
            return new BinaryProtocol.Message(capture.type(), Map.copyOf(downscaled), capture.payload());
        } catch (Exception e) {
            if (log != null) {
                log.warn(
                        "capture downscale skipped cam={} frame={} source={} reason={}",
                        cameraId,
                        frameId,
                        sourceTag,
                        e.getMessage()
                );
            }
            return capture;
        }
    }

    public Map<String, Object> downscaleHeader(Map<String, Object> header, int cameraId, String sourceTag) {
        if (header == null || header.isEmpty() || scale >= 0.999d) {
            return header;
        }
        BinaryProtocol.Message msg = new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, header, new byte[0]);
        BinaryProtocol.Message out = downscaleCapture(msg, cameraId, sourceTag);
        return out == null ? header : out.header();
    }
}
