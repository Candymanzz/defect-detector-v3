package com.example.iml.orchestrator.integration.pipeline.stages;

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

/**
 * Shared BGR SHM read / bilinear resize / write helpers for inspect and capture downscale.
 */
final class ShmBgrDownscale {

    private ShmBgrDownscale() {
    }

    static double sanitizeScale(double raw) {
        if (!Double.isFinite(raw) || raw <= 0d) {
            return 1d;
        }
        return Math.max(0.1d, Math.min(1d, raw));
    }

    static byte[] readBgr(
            Path sourcePath,
            long shmOffset,
            int width,
            int height,
            int stride
    ) throws IOException {
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

    static byte[] resizeBgr(byte[] src, int srcW, int srcH, int dstW, int dstH) {
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

    static void writeBgrFile(Path targetPath, byte[] bgr) throws IOException {
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (FileChannel ch = FileChannel.open(
                targetPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            ch.write(ByteBuffer.wrap(bgr));
        }
    }
}
