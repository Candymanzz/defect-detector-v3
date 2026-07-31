package com.example.iml.orchestrator.integration.ui.http;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;

/** JPEG encode + stable/temp file writers for UI preview. */
final class PreviewJpegEncoder {

    private static final Path PREVIEW_OUTPUT_DIR = Path.of(
            System.getProperty("java.io.tmpdir"),
            "iml-ui-current"
    );

    private PreviewJpegEncoder() {
    }

    static Path writeTempPreviewJpeg(BufferedImage image, float quality) throws IOException {
        Path out = Files.createTempFile("iml-ui-current-", ".jpg");
        if (!encodeJpeg(image, out, quality)) {
            try {
                Files.deleteIfExists(out);
            } catch (IOException ignored) {
                // best effort
            }
            return null;
        }
        return out;
    }

    static Path writeStablePreviewJpeg(int cameraId, BufferedImage image, float quality) throws IOException {
        Files.createDirectories(PREVIEW_OUTPUT_DIR);
        Path target = PREVIEW_OUTPUT_DIR.resolve("camera-" + cameraId + "-current.jpg");
        Path tmp = Files.createTempFile(PREVIEW_OUTPUT_DIR, "camera-" + cameraId + "-", ".jpg.tmp");
        boolean encoded = false;
        try {
            encoded = encodeJpeg(image, tmp, quality);
            if (!encoded) {
                return null;
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return target;
            } catch (IOException e) {
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    return target;
                } catch (IOException moveFail) {
                    // Windows can transiently lock target while frontend reads it.
                    // Fallback to direct rewrite to keep preview frames flowing.
                    if (encodeJpeg(image, target, quality)) {
                        return target;
                    }
                    return null;
                }
            }
        } finally {
            if (!encoded || Files.exists(tmp)) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    static boolean encodeJpeg(BufferedImage image, Path out, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            return false;
        }
        ImageWriter writer = writers.next();
        float q = Math.min(1f, Math.max(0.05f, quality));
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out.toFile())) {
            writer.setOutput(ios);
            ImageWriteParam p = writer.getDefaultWriteParam();
            if (p.canWriteCompressed()) {
                p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionQuality(q);
            }
            writer.write(null, new IIOImage(image, null, null), p);
            return true;
        } finally {
            writer.dispose();
        }
    }
}
