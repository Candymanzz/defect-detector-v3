package com.example.iml.orchestrator.integration.ui.artifacts;

import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.capture.ImlShmJanitor;
import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Freeze inspection SHM into a stable UI buffer; resolve positioned preview SHM name. */
public final class InspectionFrameFreezer {

    /**
     * Preview/card JPEG must show the positioned frame when positioning succeeded.
     */
    public String resolveUiPreviewShmName(Map<String, Object> cap, int cameraId) {
        if (cap == null || cap.isEmpty()) {
            return null;
        }
        Object explicit = cap.get("ui_preview_shm_name");
        if (explicit != null) {
            String name = String.valueOf(explicit).trim();
            if (!name.isEmpty() && previewShmExists(name, cameraId)) {
                return name.startsWith("/") ? name : "/" + name.replace("/", "_");
            }
        }
        if (YamlScalars.toBool(cap.get("positioning_aligned"), false)) {
            Object shm = cap.get("shm_name");
            if (shm != null) {
                String name = String.valueOf(shm).trim();
                if (name.contains("iml_pos") && previewShmExists(name, cameraId)) {
                    return name.startsWith("/") ? name : "/" + name.replace("/", "_");
                }
            }
        }
        String positioned = "/iml_pos_cam_" + cameraId;
        if (previewShmExists(positioned, cameraId)) {
            Object status = cap.get("positioning_status");
            boolean aligned = YamlScalars.toBool(cap.get("positioning_aligned"), false)
                    || "PASS".equalsIgnoreCase(String.valueOf(status == null ? "" : status));
            if (aligned) {
                return positioned;
            }
        }
        return null;
    }

    @SuppressWarnings("unused")
    public FrozenFrame freezeInspectionFrame(
            int cameraId,
            long frameId,
            String shmName,
            int width,
            int height,
            int stride,
            Map<String, Object> captureHeader
    ) throws IOException {
        if (width <= 0 || height <= 0 || stride < width * 3) {
            throw new IOException("invalid frame geometry");
        }
        long sourceOffset = YamlScalars.toLong(captureHeader.get("shm_offset"), 0L);
        long frameBytes = Math.multiplyExact((long) stride, (long) height);
        Path source = FrameJpegWriter.resolveShmPath(shmName, cameraId);
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("source SHM is missing");
        }

        String base = shmName.startsWith("/") ? shmName.substring(1) : shmName;
        base = base.replace('/', '_');
        // Line-pin files are per-cycle; always copy into a stable UI buffer so the pin can be deleted
        // immediately after freeze without racing the async JPEG publisher.
        boolean ephemeralPin = YamlScalars.toBool(captureHeader.get("line_pinned"), false)
                || ImlShmJanitor.isEphemeralLinePin(base);
        if (sourceOffset == 0L && !ephemeralPin && ImlShmJanitor.isDedicatedOrchestratorBuffer(base)) {
            return new FrozenFrame(source, "/" + base, false);
        }

        String frozenName = "iml_ui_inspect_cam_" + cameraId;
        Path target = FrameJpegWriter.imlShmFilePath(frozenName);
        Files.createDirectories(target.getParent());
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(
                     target,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING
             )) {
            if (input.size() < sourceOffset + frameBytes) {
                throw new IOException("source SHM is smaller than the captured frame");
            }
            long copied = 0L;
            while (copied < frameBytes) {
                long count = input.transferTo(sourceOffset + copied, frameBytes - copied, output);
                if (count <= 0L) {
                    throw new IOException("could not copy the complete captured frame");
                }
                copied += count;
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        // Stable overwrite name — keep for next frame; pin cleanup happens via ImlShmJanitor.
        return new FrozenFrame(target, "/" + frozenName, false);
    }

    private static boolean previewShmExists(String shmName, int cameraId) {
        Path path = FrameJpegWriter.resolveShmPath(shmName, cameraId);
        return path != null && Files.isRegularFile(path);
    }
}
