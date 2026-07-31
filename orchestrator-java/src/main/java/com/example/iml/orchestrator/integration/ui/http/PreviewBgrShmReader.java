package com.example.iml.orchestrator.integration.ui.http;

import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Reads BGR frames from SHM files into {@link BufferedImage}. */
final class PreviewBgrShmReader {

    private PreviewBgrShmReader() {
    }

    static BufferedImage readBgrImageFromShm(
            String shmName,
            int width,
            int height,
            int stride,
            long shmOffset,
            int cameraId
    ) throws IOException {
        if (width <= 0 || height <= 0 || stride < width * 3 || shmOffset < 0) {
            throw new IOException(
                    "invalid frame geometry width=" + width + " height=" + height + " stride=" + stride
                            + " shmOffset=" + shmOffset
            );
        }
        Path shmPath = FrameJpegWriter.resolveShmPath(shmName, cameraId);
        if (shmPath == null || !Files.isRegularFile(shmPath)) {
            throw new IOException("shm not readable shmName=" + shmName + " path=" + shmPath);
        }
        long need = (long) stride * (long) height;
        try (FileChannel ch = FileChannel.open(shmPath, StandardOpenOption.READ)) {
            long fileSize = Math.max(0, ch.size());
            if (fileSize < shmOffset + need || need < width * 3L * height) {
                throw new IOException(
                        "shm size mismatch fileSize=" + fileSize + " need=" + need + " shmOffset=" + shmOffset
                );
            }
            // Avoid FileChannel.map here: on Windows a mapped section keeps the SHM file locked and
            // breaks the next freeze/JPEG write into iml_ui_inspect_cam_*.
            byte[] raw = new byte[Math.toIntExact(need)];
            ByteBuffer readBuf = ByteBuffer.wrap(raw);
            int totalRead = 0;
            while (totalRead < need) {
                int read = ch.read(readBuf, shmOffset + totalRead);
                if (read <= 0) {
                    throw new IOException("shm read incomplete at offset=" + (shmOffset + totalRead));
                }
                totalRead += read;
            }
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            byte[] dst = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < height; y++) {
                System.arraycopy(raw, y * stride, dst, y * width * 3, width * 3);
            }
            return img;
        }
    }
}
