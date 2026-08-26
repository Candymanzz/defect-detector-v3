package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JPEG bytes → BGR8 SHM file under iml_shm /dev/shm.
 */
public final class JpegBgrShmWriter {

    public record WrittenFrame(
            String shmName,
            Path shmPath,
            int width,
            int height,
            int stride,
            Map<String, Object> captureHeader
    ) {
    }

    private JpegBgrShmWriter() {
    }

    public static WrittenFrame write(
            byte[] jpegBytes,
            int cameraId,
            long frameId,
            String shmFileBase
    ) throws IOException {
        if (jpegBytes == null || jpegBytes.length == 0) {
            throw new IOException("empty jpeg");
        }
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(jpegBytes));
        if (src == null) {
            throw new IOException("cannot decode jpeg");
        }
        int width = src.getWidth();
        int height = src.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IOException("invalid jpeg size");
        }
        BufferedImage bgrImage = toType3ByteBgr(src);
        byte[] bgr = ((DataBufferByte) bgrImage.getRaster().getDataBuffer()).getData();
        int stride = width * 3;
        String base = shmFileBase == null || shmFileBase.isBlank()
                ? "iml_uitest_cam" + cameraId
                : shmFileBase;
        Path shmPath = FrameJpegWriter.imlShmFilePath(base);
        Path parent = shmPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (FileChannel ch = FileChannel.open(
                shmPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            ch.write(ByteBuffer.wrap(bgr));
        }
        String shmName = "/" + base;
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("op", "capture");
        header.put("status", "OK");
        header.put("camera_id", cameraId);
        header.put("frame_id", frameId);
        header.put("shm_name", shmName);
        header.put("shm_offset", 0L);
        header.put("width", width);
        header.put("height", height);
        header.put("stride", stride);
        header.put("pixel_format", "bgr_u8");
        header.put("channels", 3);
        header.put("timestamp_ns", System.nanoTime());
        header.put("test_analyze", true);
        return new WrittenFrame(shmName, shmPath, width, height, stride, Map.copyOf(header));
    }

    /**
     * Copy a SHM slice into a uniquely named buffer. Does not fall back to {@code iml_cam_N_frame}.
     */
    public static Path snapshotNamedBuffer(String sourceShmName, String destBase, long sourceOffset, long frameBytes)
            throws IOException {
        if (sourceShmName == null || sourceShmName.isBlank() || destBase == null || destBase.isBlank()) {
            throw new IOException("missing shm names for test snapshot");
        }
        if (frameBytes <= 0L) {
            throw new IOException("invalid frame size for test snapshot");
        }
        String srcBase = sourceShmName.startsWith("/") ? sourceShmName.substring(1) : sourceShmName;
        srcBase = srcBase.replace('/', '_');
        Path source = FrameJpegWriter.imlShmFilePath(srcBase);
        if (!Files.isRegularFile(source)) {
            throw new IOException("source SHM missing: " + source);
        }
        Path target = FrameJpegWriter.imlShmFilePath(destBase);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(
                     target,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING
             )) {
            if (input.size() < sourceOffset + frameBytes) {
                throw new IOException("source SHM is smaller than the captured frame");
            }
            long copied = 0L;
            while (copied < frameBytes) {
                long count = input.transferTo(sourceOffset + copied, frameBytes - copied, output);
                if (count <= 0L) {
                    throw new IOException("could not copy the complete captured frame");
                }
                copied += count;
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        return target;
    }

    public static void deleteQuietly(Path shmPath) {
        if (shmPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(shmPath);
        } catch (IOException ignored) {
        }
    }

    private static BufferedImage toType3ByteBgr(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return src;
        }
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return dst;
    }
}
