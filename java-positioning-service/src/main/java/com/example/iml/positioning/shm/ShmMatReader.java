package com.example.iml.positioning.shm;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads BGR frames from iml_shm files.
 * Uses RandomAccessFile + reusable byte[] (no MappedByteBuffer) so RSS does not
 * accumulate GC-delayed mmap regions on every frame.
 */
public final class ShmMatReader {

    private byte[] shmReadBuffer = new byte[0];
    private byte[] shmRowBuffer = new byte[0];

    public static Path resolveShmPath(String shmName) {
        if (shmName == null || shmName.isBlank()) {
            throw new IllegalArgumentException("shm_name is empty");
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            Path direct = Path.of(shmName.trim());
            if (direct.isAbsolute()) {
                if (Files.isRegularFile(direct)) {
                    return direct;
                }
                String name = direct.getFileName().toString();
                if (name.endsWith(".bin")) {
                    Path alt = windowsImlShmDir().resolve(name.substring(0, name.length() - 4));
                    if (Files.isRegularFile(alt)) {
                        return alt;
                    }
                }
                if (name.startsWith("iml_cam_")) {
                    Path alt = windowsImlShmDir().resolve(name);
                    if (Files.isRegularFile(alt)) {
                        return alt;
                    }
                }
                return direct;
            }
            String key = shmName.startsWith("/") ? shmName.substring(1) : shmName;
            key = key.replace("/", "_");
            return windowsImlShmDir().resolve(key);
        }
        String key = shmName.startsWith("/") ? shmName.substring(1) : shmName;
        return Path.of("/dev/shm", key);
    }

    private static Path windowsImlShmDir() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = System.getenv("TEMP");
        }
        if (base == null || base.isBlank()) {
            base = ".";
        }
        return Path.of(base, "iml_shm");
    }

    public Mat readShmMat(Map<String, Object> h) {
        String shmName = str(h.get("shm_name"));
        int width = (int) num(h.get("width"), 1224);
        int height = (int) num(h.get("height"), 1024);
        int stride = (int) num(h.get("stride"), width * 3);
        int offset = (int) num(h.get("shm_offset"), 0);
        Path shmPath = resolveShmPath(shmName);

        Mat mat = new Mat(height, width, CvType.CV_8UC3);
        try (RandomAccessFile raf = new RandomAccessFile(shmPath.toFile(), "r")) {
            int expected = width * 3;
            if (stride == expected) {
                byte[] all = ensureShmReadBuffer(height * stride);
                raf.seek(offset);
                raf.readFully(all, 0, height * stride);
                mat.put(0, 0, all);
            } else {
                byte[] row = ensureShmRowBuffer(expected);
                for (int y = 0; y < height; y++) {
                    raf.seek(offset + (long) y * stride);
                    raf.readFully(row, 0, expected);
                    mat.put(y, 0, row);
                }
            }
            return mat;
        } catch (Exception e) {
            mat.release();
            throw new IllegalStateException("Failed to read shm frame: " + shmPath, e);
        }
    }

    private byte[] ensureShmReadBuffer(int required) {
        if (shmReadBuffer.length < required) {
            shmReadBuffer = new byte[required];
        }
        return shmReadBuffer;
    }

    private byte[] ensureShmRowBuffer(int required) {
        if (shmRowBuffer.length < required) {
            shmRowBuffer = new byte[required];
        }
        return shmRowBuffer;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static double num(Object o, double fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return fallback;
        }
        return Double.parseDouble(String.valueOf(o));
    }
}
