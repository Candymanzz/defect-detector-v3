package com.example.iml.positioning.shm;

import org.opencv.core.Mat;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ShmMatWriter {

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
            try (FileChannel ch = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                ch.write(ByteBuffer.wrap(bytes, 0, needed));
            }
            return path;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write shm frame: " + path, e);
        }
    }

    private byte[] ensureWriteBuffer(int required) {
        if (writeBuffer.length < required) {
            writeBuffer = new byte[required];
        }
        return writeBuffer;
    }
}
