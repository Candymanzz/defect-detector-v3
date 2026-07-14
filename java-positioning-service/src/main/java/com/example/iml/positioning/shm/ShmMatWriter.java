package com.example.iml.positioning.shm;

import org.opencv.core.Mat;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes BGR frames into iml_shm files.
 * On Windows, never TRUNCATE/recreate when another process may still have a mapping open —
 * that yields "user-mapped section open". Overwrite in place instead.
 */
public final class ShmMatWriter {

    private static final int WRITE_RETRIES = 8;
    private static final long RETRY_SLEEP_MS = 15;

    private byte[] writeBuffer = new byte[0];

    public Path writeBgrMat(String shmName, Mat bgr) {
        if (bgr == null || bgr.empty() || bgr.channels() != 3) {
            throw new IllegalArgumentException("expected non-empty BGR Mat");
        }
        Path path = ShmMatReader.resolveShmPath(shmName);
        Path parent = path.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            int width = bgr.cols();
            int height = bgr.rows();
            int needed = width * height * 3;
            byte[] bytes = ensureWriteBuffer(needed);
            bgr.get(0, 0, bytes);

            Exception last = null;
            for (int attempt = 1; attempt <= WRITE_RETRIES; attempt++) {
                try {
                    writeInPlace(path, bytes, needed);
                    return path;
                } catch (Exception e) {
                    last = e;
                    if (attempt < WRITE_RETRIES && isWindowsMappingBusy(e)) {
                        Thread.sleep(RETRY_SLEEP_MS * attempt);
                        continue;
                    }
                    break;
                }
            }
            throw new IllegalStateException("Failed to write shm frame: " + path, last);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write shm frame: " + path, e);
        }
    }

    private static void writeInPlace(Path path, byte[] bytes, int needed) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            if (raf.length() < needed) {
                raf.setLength(needed);
            }
            raf.seek(0);
            raf.write(bytes, 0, needed);
            raf.getFD().sync();
        }
    }

    private static boolean isWindowsMappingBusy(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase().contains("user-mapped section")) {
                return true;
            }
        }
        return false;
    }

    private byte[] ensureWriteBuffer(int required) {
        if (writeBuffer.length < required) {
            writeBuffer = new byte[required];
        }
        return writeBuffer;
    }
}
