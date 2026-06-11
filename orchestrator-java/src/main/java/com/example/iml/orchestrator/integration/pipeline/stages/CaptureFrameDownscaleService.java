package com.example.iml.orchestrator.integration.pipeline.stages;

import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        this.scale = sanitizeScale(scale);
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
            byte[] bgr = readBgr(sourcePath, shmOffset, width, height, stride);
            int outW = Math.max(1, (int) Math.round(width * scale));
            int outH = Math.max(1, (int) Math.round(height * scale));
            byte[] outBgr = resizeBgr(bgr, width, height, outW, outH);
            String base = sanitizeForFileName(shmName, cameraId);
            String outName = "iml_ds_" + sourceTag + "_cam" + resolvedCameraId + "_f" + Math.max(frameId, 0L) + "_" + base;
            Path outPath = FrameJpegWriter.imlShmFilePath(outName);
            Path parent = outPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (FileChannel ch = FileChannel.open(
                    outPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                ch.write(ByteBuffer.wrap(outBgr));
            }
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

    private static double sanitizeScale(double raw) {
        if (!Double.isFinite(raw) || raw <= 0d) {
            return 1d;
        }
        return Math.max(0.1d, Math.min(1d, raw));
    }

    private static byte[] resizeBgr(byte[] src, int srcW, int srcH, int dstW, int dstH) {
        BufferedImage srcImg = new BufferedImage(srcW, srcH, BufferedImage.TYPE_3BYTE_BGR);
        System.arraycopy(src, 0, ((DataBufferByte) srcImg.getRaster().getDataBuffer()).getData(), 0, src.length);
        BufferedImage dstImg = new BufferedImage(dstW, dstH, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = dstImg.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            g.drawImage(srcImg, 0, 0, dstW, dstH, null);
        } finally {
            g.dispose();
        }
        return ((DataBufferByte) dstImg.getRaster().getDataBuffer()).getData();
    }

    private static byte[] readBgr(Path sourcePath, long shmOffset, int width, int height, int stride) throws IOException {
        long needed = (long) stride * (long) height;
        try (FileChannel ch = FileChannel.open(sourcePath, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < shmOffset + needed) {
                throw new IOException("source shm too small");
            }
            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, shmOffset, needed);
            byte[] bgr = new byte[width * height * 3];
            for (int y = 0; y < height; y++) {
                buf.position(y * stride);
                buf.get(bgr, y * width * 3, width * 3);
            }
            return bgr;
        }
    }

    private static String sanitizeForFileName(String shmName, int cameraId) {
        String base = shmName;
        if (base.startsWith("/")) {
            base = base.substring(1);
        }
        base = base.replace('\\', '_').replace('/', '_').replace(':', '_');
        if (base.isBlank()) {
            return "cam" + cameraId;
        }
        return base;
    }
}
