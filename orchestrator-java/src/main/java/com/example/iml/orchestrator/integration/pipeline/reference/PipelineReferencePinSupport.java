package com.example.iml.orchestrator.integration.pipeline.reference;

import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.clientws.bundle.PixelRoi;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceViewSlot;
import com.example.iml.orchestrator.integration.clientws.bundle.ShmFrameRefData;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.roi.InterestPolygonNormCodec;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable SHM pin + capture-header builders for {@link PipelineReferenceRegistry}. */
final class PipelineReferencePinSupport {

    /** Совпадает с {@code BucketInspectionConfig.DEFAULT_CAMERAS_PER_PRESET_GROUP}. */
    static final int CAMERAS_PER_BUCKET = 5;

    private PipelineReferencePinSupport() {
    }

    /**
     * Копирует пиксели эталона в {@code iml_ref_cam{N}} (не перезаписывается line-pin / ring buffer).
     */
    static Map<String, Object> pinDurableReference(Map<String, Object> header, int cameraId) throws IOException {
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
        String currentBase = shmName.startsWith("/") ? shmName.substring(1) : shmName;
        currentBase = currentBase.replace('/', '_');
        // Уже стабильный путь с offset=0 — повторно копировать не обязательно.
        if (durableBase.equals(currentBase) && shmOffset == 0L) {
            Path existing = FrameJpegWriter.imlShmFilePath(durableBase);
            if (Files.isRegularFile(existing)) {
                return header;
            }
        }
        Path sourcePath = FrameJpegWriter.resolveShmPath(shmName, resolvedCameraId);
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
            throw new IOException("reference shm not found: " + shmName);
        }
        long frameBytes = (long) stride * (long) height;
        Path destPath = FrameJpegWriter.imlShmFilePath(durableBase);
        Path parent = destPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        copyRegion(sourcePath, shmOffset, destPath, frameBytes);
        Map<String, Object> pinned = new LinkedHashMap<>(header);
        pinned.put("shm_name", "/" + durableBase);
        pinned.put("shm_offset", 0L);
        pinned.put("reference_pinned", true);
        pinned.put("reference_pin_source_shm", shmName);
        pinned.put("reference_pin_source_offset", shmOffset);
        return Map.copyOf(pinned);
    }

    static void copyRegion(Path sourcePath, long sourceOffset, Path destPath, long bytes) throws IOException {
        try (FileChannel src = FileChannel.open(sourcePath, StandardOpenOption.READ);
             FileChannel dst = FileChannel.open(
                     destPath,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING
             )) {
            long fileSize = src.size();
            if (fileSize < sourceOffset + bytes) {
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

    static int resolveBucketGroupId(ReferenceBundleSnapshot snap) {
        int minCameraId = snap.views().stream()
                .mapToInt(slot -> slot.frame().cameraId())
                .min()
                .orElse(0);
        return Math.max(0, minCameraId / CAMERAS_PER_BUCKET);
    }

    static Map<String, Object> frameToCaptureHeader(
            ShmFrameRefData frame,
            ReferenceViewSlot slot,
            int jointCameraId,
            int bucketGroupId,
            Map<String, Object> bucketJointRoiNorm
    ) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("camera_id", frame.cameraId());
        header.put("frame_id", frame.frameId());
        header.put("shm_name", frame.shmName());
        header.put("shm_offset", frame.shmOffset());
        header.put("width", frame.width());
        header.put("height", frame.height());
        header.put("stride", frame.strideBytes());
        header.put("client_reference_bundle", true);
        header.put("bucket_group_id", bucketGroupId);
        header.put("joint_camera_id", jointCameraId);
        if (frame.pixelFormat() != null && !frame.pixelFormat().isBlank()) {
            header.put("format", frame.pixelFormat());
        }
        List<java.util.Map<String, Object>> polygonNorm = slot.hasInterestPolygonNorm()
                ? InterestPolygonNormCodec.fromNormPoints(slot.interestPolygonNorm())
                : InterestPolygonNormCodec.fromPixelRoi(slot.interestRoi(), frame.width(), frame.height());
        if (polygonNorm.size() >= 3) {
            header.put("interest_polygon_norm", polygonNorm);
        }
        if (bucketJointRoiNorm != null) {
            header.put("joint_roi_norm", bucketJointRoiNorm);
        }
        return header;
    }

    static Map<String, Object> normalizedRoi(PixelRoi roi, int frameWidth, int frameHeight) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("x", roi.x() / (double) frameWidth);
        normalized.put("y", roi.y() / (double) frameHeight);
        normalized.put("width", roi.width() / (double) frameWidth);
        normalized.put("height", roi.height() / (double) frameHeight);
        return Map.copyOf(normalized);
    }
}
