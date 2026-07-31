package com.example.iml.orchestrator.integration.pipeline.reference;

import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

final class DurableReferencePin {
    private DurableReferencePin() {
    }

    static Map<String, Object> pin(Map<String, Object> header, int cameraId) throws IOException {
        if (header == null || header.isEmpty()) {
            throw new IOException("empty reference header");
        }
        String shmName = String.valueOf(header.getOrDefault("shm_name", "")).trim();
        int width = YamlScalars.toInt(header.get("width"), 0);
        int height = YamlScalars.toInt(header.get("height"), 0);
        int stride = YamlScalars.toInt(header.get("stride"), 0);
        long shmOffset = YamlScalars.toLong(header.get("shm_offset"), 0L);
        int resolvedCameraId = YamlScalars.toInt(header.get("camera_id"), cameraId);
        if (shmName.isBlank() || width <= 0 || height <= 0 || stride < width * 3 || shmOffset < 0L) {
            throw new IOException("invalid reference frame geometry for cam=" + resolvedCameraId);
        }
        String durableBase = "iml_ref_cam" + resolvedCameraId;
        String currentBase = (shmName.startsWith("/") ? shmName.substring(1) : shmName).replace('/', '_');
        if (durableBase.equals(currentBase) && shmOffset == 0L
                && Files.isRegularFile(FrameJpegWriter.imlShmFilePath(durableBase))) {
            return header;
        }
        Path sourcePath = FrameJpegWriter.resolveShmPath(shmName, resolvedCameraId);
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
            throw new IOException("reference shm not found: " + shmName);
        }
        Path destPath = FrameJpegWriter.imlShmFilePath(durableBase);
        Path parent = destPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        copyRegion(sourcePath, shmOffset, destPath, (long) stride * height);
        Map<String, Object> pinned = new LinkedHashMap<>(header);
        pinned.put("shm_name", "/" + durableBase);
        pinned.put("shm_offset", 0L);
        pinned.put("reference_pinned", true);
        pinned.put("reference_pin_source_shm", shmName);
        pinned.put("reference_pin_source_offset", shmOffset);
        return Map.copyOf(pinned);
    }

    private static void copyRegion(Path sourcePath, long sourceOffset, Path destPath, long bytes) throws IOException {
        try (FileChannel src = FileChannel.open(sourcePath, StandardOpenOption.READ);
             FileChannel dst = FileChannel.open(destPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            if (src.size() < sourceOffset + bytes) {
                throw new IOException("reference shm too small: " + sourcePath);
            }
            long position = sourceOffset;
            long remaining = bytes;
            while (remaining > 0L) {
                long transferred = src.transferTo(position, remaining, dst);
                if (transferred <= 0L) {
                    throw new IOException("reference shm copy stalled at offset " + position);
                }
                position += transferred;
                remaining -= transferred;
            }
        }
    }
}
