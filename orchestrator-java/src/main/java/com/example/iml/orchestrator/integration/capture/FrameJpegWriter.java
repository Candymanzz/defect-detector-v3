package com.example.iml.orchestrator.integration.capture;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import org.apache.logging.log4j.Logger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Map;

/**
 * Сохранение кадра из файла SHM в JPEG ({@code integration.save_captures}).
 */
public final class FrameJpegWriter {

    private final Logger log;

    public FrameJpegWriter(Logger log) {
        this.log = log;
    }

    public void saveCapturedFrame(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig cfg,
            Map<String, Object> header,
            String phaseLabel
    ) {
        if (!cfg.enabled() || header == null || header.isEmpty()) {
            return;
        }
        int cam = YamlScalars.toInt(header.get("camera_id"), -1);
        long fid = YamlScalars.toLong(header.get("frame_id"), -1L);
        int w = YamlScalars.toInt(header.get("width"), 0);
        int h = YamlScalars.toInt(header.get("height"), 0);
        int stride = YamlScalars.toInt(header.get("stride"), 0);
        if (cam < 0 || fid < 0 || w <= 0 || h <= 0 || stride < w * 3) {
            return;
        }
        Object sn = header.get("shm_name");
        if (sn == null) {
            return;
        }
        String shmName = String.valueOf(sn);
        if (shmName.isEmpty()) {
            return;
        }
        long offset = YamlScalars.toLong(header.get("shm_offset"), 0L);
        long need = (long) stride * (long) h;
        long frameBytes = YamlScalars.toLong(header.get("frame_bytes"), need);
        if (frameBytes < need) {
            frameBytes = need;
        }
        String base = shmName.startsWith("/") ? shmName.substring(1) : shmName;
        base = base.replace('/', '_');
        Path shmPath = imlShmFilePath(base);
        if (!Files.isRegularFile(shmPath)) {
            log.warn("save_captures: нет файла SHM {}", shmPath);
            return;
        }
        Path outDir = projectRoot.resolve(cfg.relativeDir()).normalize();
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            log.warn("save_captures: не удалось создать {}: {}", outDir, e.getMessage());
            return;
        }
        Path outFile = outDir.resolve(String.format("%s_cam%d_f%07d.jpg", phaseLabel, cam, fid));
        try {
            writeBgrShmSliceToJpeg(shmPath, offset, w, h, stride, need, outFile, cfg.jpegQuality());
            log.debug("save_captures: {}", outFile);
        } catch (IOException e) {
            log.warn("save_captures: {} — {}", outFile, e.getMessage());
        }
    }

    /** Каталог общей памяти: Linux /dev/shm, Windows %LOCALAPPDATA%\\iml_shm. */
    public static Path imlShmFilePath(String fileNameInShmDir) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String la = System.getenv("LOCALAPPDATA");
            if (la == null || la.isBlank()) {
                la = System.getenv("TEMP");
            }
            if (la == null || la.isBlank()) {
                la = ".";
            }
            return Path.of(la, "iml_shm", fileNameInShmDir);
        }
        return Path.of("/dev/shm", fileNameInShmDir);
    }

    private static void writeBgrShmSliceToJpeg(
            Path shmPath,
            long offset,
            int width,
            int height,
            int stride,
            long regionBytes,
            Path outJpeg,
            float quality
    ) throws IOException {
        try (FileChannel ch = FileChannel.open(shmPath, StandardOpenOption.READ)) {
            long fileSize = ch.size();
            if (fileSize < offset + regionBytes) {
                throw new IOException("shm file too small: size=" + fileSize + " offset=" + offset + " region=" + regionBytes);
            }
            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, offset, regionBytes);
            byte[] bgr = new byte[width * height * 3];
            for (int y = 0; y < height; y++) {
                buf.position(y * stride);
                buf.get(bgr, y * width * 3, width * 3);
            }
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            byte[] dst = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            System.arraycopy(bgr, 0, dst, 0, bgr.length);
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) {
                throw new IOException("no JPEG ImageWriter");
            }
            ImageWriter writer = writers.next();
            ImageWriteParam wp = writer.getDefaultWriteParam();
            if (wp.canWriteCompressed()) {
                wp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                wp.setCompressionQuality(quality);
            }
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(Files.newOutputStream(outJpeg))) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), wp);
            }
            writer.dispose();
        }
    }
}
