package com.example.iml.orchestrator.integration.ui;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class HeatmapU8PreviewScaler {

    record ScaledHeatmap(Path path, int width, int height) {
    }

    private HeatmapU8PreviewScaler() {
    }

    static ScaledHeatmap scale(Path source, int width, int height, int maxWidth) throws IOException {
        validateSource(source, width, height);
        if (maxWidth <= 0 || width <= maxWidth) {
            return new ScaledHeatmap(source, width, height);
        }

        int targetWidth = maxWidth;
        int targetHeight = Math.max(1, (int) Math.round((double) height * targetWidth / width));
        byte[] sourceBytes = Files.readAllBytes(source);
        byte[] targetBytes = new byte[Math.multiplyExact(targetWidth, targetHeight)];

        for (int targetY = 0; targetY < targetHeight; targetY++) {
            int sourceY = Math.min(height - 1, (int) ((long) targetY * height / targetHeight));
            int sourceRow = sourceY * width;
            int targetRow = targetY * targetWidth;
            for (int targetX = 0; targetX < targetWidth; targetX++) {
                int sourceX = Math.min(width - 1, (int) ((long) targetX * width / targetWidth));
                targetBytes[targetRow + targetX] = sourceBytes[sourceRow + sourceX];
            }
        }

        Path target = previewPath(source, targetWidth, targetHeight);
        writeAtomically(target, targetBytes);
        return new ScaledHeatmap(target, targetWidth, targetHeight);
    }

    private static void validateSource(Path source, int width, int height) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("heatmap source file is missing: " + source);
        }
        if (width <= 0 || height <= 0) {
            throw new IOException("invalid heatmap dimensions: " + width + "x" + height);
        }
        long expectedBytes = Math.multiplyExact((long) width, height);
        long actualBytes = Files.size(source);
        if (actualBytes < expectedBytes) {
            throw new IOException(
                    "heatmap file is too small: " + actualBytes + " bytes, expected at least " + expectedBytes
            );
        }
    }

    private static Path previewPath(Path source, int width, int height) {
        String fileName = source.getFileName().toString();
        return source.resolveSibling(fileName + ".ui-" + width + "x" + height + ".u8");
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    tmp,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
