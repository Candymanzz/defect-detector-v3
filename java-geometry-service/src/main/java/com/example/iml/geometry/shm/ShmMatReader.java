package com.example.iml.geometry.shm;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Map;

public final class ShmMatReader {

    private byte[] shmReadBuffer = new byte[0];
    private byte[] shmRowBuffer = new byte[0];

    /** Linux: /dev/shm. Windows: %LOCALAPPDATA%\\iml_shm (как в camera-worker). */
    public static Path resolveShmPath(String shmName) {
        String key = shmName.startsWith("/") ? shmName.substring(1) : shmName;
        key = key.replace("/", "_");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String base = System.getenv("LOCALAPPDATA");
            if (base == null || base.isBlank()) {
                base = System.getenv("TEMP");
            }
            if (base == null || base.isBlank()) {
                base = ".";
            }
            return Path.of(base, "iml_shm", key);
        }
        return Path.of("/dev/shm", key);
    }

    public Mat readShmMat(Map<String, Object> h) {
        String shmName = str(h.get("shm_name"));
        int width = (int) num(h.get("width"), 2448);
        int height = (int) num(h.get("height"), 2048);
        int stride = (int) num(h.get("stride"), width * 3);
        int offset = (int) num(h.get("shm_offset"), 0);
        Path shmPath = resolveShmPath(shmName);

        try (RandomAccessFile raf = new RandomAccessFile(shmPath.toFile(), "r");
             FileChannel channel = raf.getChannel()) {
            MappedByteBuffer mb = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            Mat mat = new Mat(height, width, CvType.CV_8UC3);
            try {
                int expected = width * 3;
                if (stride == expected) {
                    byte[] all = ensureShmReadBuffer(height * stride);
                    mb.position(offset);
                    mb.get(all);
                    mat.put(0, 0, all);
                } else {
                    byte[] row = ensureShmRowBuffer(expected);
                    for (int y = 0; y < height; y++) {
                        mb.position(offset + y * stride);
                        mb.get(row);
                        mat.put(y, 0, row);
                    }
                }
                return mat;
            } catch (Exception ex) {
                mat.release();
                throw ex;
            }
        } catch (Exception e) {
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
