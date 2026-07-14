package com.example.iml.orchestrator.integration.capture;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Копирует кадр из ring-SHM воркера в отдельный файл инспекции.
 * Съёмка может сразу идти на следующий триггер, пока пайплайн читает закреплённый кадр.
 */
public final class LineFramePinService {

    private static final Logger LOG = LogManager.getLogger(LineFramePinService.class);

    public BinaryProtocol.Message pinCapture(BinaryProtocol.Message capture, int cameraId) {
        if (capture == null || capture.header() == null) {
            return capture;
        }
        Map<String, Object> src = capture.header();
        String shmName = String.valueOf(src.getOrDefault("shm_name", "")).trim();
        int width = YamlScalars.toInt(src.get("width"), 0);
        int height = YamlScalars.toInt(src.get("height"), 0);
        int stride = YamlScalars.toInt(src.get("stride"), 0);
        long shmOffset = YamlScalars.toLong(src.get("shm_offset"), 0L);
        long frameId = YamlScalars.toLong(src.get("frame_id"), -1L);
        int resolvedCameraId = YamlScalars.toInt(src.get("camera_id"), cameraId);
        long frameBytes = YamlScalars.toLong(src.get("frame_bytes"), (long) stride * (long) height);
        long need = (long) stride * (long) height;
        if (frameBytes < need) {
            frameBytes = need;
        }
        if (shmName.isBlank() || width <= 0 || height <= 0 || stride < width * 3 || shmOffset < 0L || frameId < 0L) {
            return capture;
        }
        try {
            Path sourcePath = FrameJpegWriter.resolveShmPath(shmName, resolvedCameraId);
            if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
                throw new IOException("source shm not found: " + shmName);
            }
            String pinName = "iml_line_pin_cam" + resolvedCameraId + "_f" + frameId;
            Path pinPath = FrameJpegWriter.imlShmFilePath(pinName);
            Path parent = pinPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            copyRegion(sourcePath, shmOffset, pinPath, frameBytes);
            Map<String, Object> pinned = new LinkedHashMap<>(src);
            pinned.put("shm_name", "/" + pinName);
            pinned.put("shm_offset", 0L);
            pinned.put("line_pinned", true);
            pinned.put("line_pin_source_shm", shmName);
            pinned.put("line_pin_source_offset", shmOffset);
            return new BinaryProtocol.Message(capture.type(), Map.copyOf(pinned), capture.payload());
        } catch (Exception e) {
            LOG.warn(
                    "line frame pin skipped cam={} frame={}: {}",
                    resolvedCameraId,
                    frameId,
                    e.getMessage()
            );
            return capture;
        }
    }

    /** Удаляет file-pin после того, как пайплайн/UI больше не читают этот кадр. */
    public static void releasePinnedCapture(Map<String, Object> header) {
        ImlShmJanitor.releaseEphemeralCaptureBuffers(header, LOG);
    }

    private static void copyRegion(Path sourcePath, long sourceOffset, Path destPath, long bytes) throws IOException {
        try (FileChannel src = FileChannel.open(sourcePath, StandardOpenOption.READ);
             FileChannel dst = FileChannel.open(
                     destPath,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING
             )) {
            long fileSize = src.size();
            if (fileSize < sourceOffset + bytes) {
                throw new IOException("source shm too small");
            }
            long position = sourceOffset;
            long remaining = bytes;
            while (remaining > 0L) {
                long transferred = src.transferTo(position, remaining, dst);
                if (transferred <= 0L) {
                    throw new IOException("shm copy stalled at offset " + position);
                }
                position += transferred;
                remaining -= transferred;
            }
        }
    }
}
