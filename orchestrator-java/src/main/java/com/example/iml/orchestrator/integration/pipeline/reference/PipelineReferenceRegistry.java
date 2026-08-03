package com.example.iml.orchestrator.integration.pipeline.reference;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.clientws.bundle.PixelRoi;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceViewSlot;
import com.example.iml.orchestrator.integration.clientws.bundle.ShmFrameRefData;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.BinaryInspectHeaders;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.roi.InterestPolygonNormCodec;
import com.example.iml.orchestrator.integration.pipeline.spi.CameraCaptureStage;
import com.example.iml.orchestrator.integration.python.AnalisSurfacePoolSupport;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Эталоны для пайплайна: из камеры ({@code reference_source=camera}) или от клиента по WS.
 * После двух пакетов эталонов (ведро 0–4 и 5–9) хранит по одному {@link ReferenceSnapshot} на камеру,
 * как analisSurface хранит {@code references[product#cam=id]}.
 * <p>
 * Кадры из {@code client.reference_bundle} копируются в стабильный {@code iml_ref_cam{N}},
 * чтобы line-pin ({@code iml_line_pin_*}) и чистка SHM не ломали путь эталона.
 */
public final class PipelineReferenceRegistry {

    /** Совпадает с {@code BucketInspectionConfig.DEFAULT_CAMERAS_PER_PRESET_GROUP}. */
    private static final int CAMERAS_PER_BUCKET = 5;

    private final Map<Integer, ReferenceSnapshot> byCamera = new ConcurrentHashMap<>();

    public Map<Integer, ReferenceSnapshot> byCamera() {
        return byCamera;
    }

    public ReferenceSnapshot get(int cameraId) {
        return byCamera.get(cameraId);
    }

    /** Сброс эталонов пайплайна (остановка инспекции / clear reference). */
    public void clear() {
        byCamera.clear();
    }

    /**
     * После {@code client.reference_bundle}: SHM-заголовки в реестр + {@code set_reference_shm} в Python.
     */
    public void applyClientBundle(
            Logger log,
            ReferenceBundleSnapshot snap,
            IntFunction<String> detectorIdResolver,
            List<? extends BinaryRpcSupervisor> pythonPool,
            CameraCaptureStage captureStage
    ) throws Exception {
        int jointCameraId = snap.views().get(snap.jointViewIndex()).frame().cameraId();
        int bucketGroupId = resolveBucketGroupId(snap);
        ReferenceViewSlot jointSlot = snap.views().get(snap.jointViewIndex());
        ShmFrameRefData jointFrame = jointSlot.frame();
        Map<String, Object> bucketJointRoiNorm = jointSlot.jointRoi() == null
                ? null
                : normalizedRoi(jointSlot.jointRoi(), jointFrame.width(), jointFrame.height());
        List<Map<String, Object>> bucketJointPolygonNorm = jointSlot.hasJointPolygonNorm()
                ? InterestPolygonNormCodec.fromNormPoints(jointSlot.jointPolygonNorm())
                : List.of();

        for (ReferenceViewSlot slot : snap.views()) {
            ShmFrameRefData frame = slot.frame();
            Map<String, Object> header = frameToCaptureHeader(
                    frame,
                    slot,
                    jointCameraId,
                    bucketGroupId,
                    bucketJointRoiNorm,
                    bucketJointPolygonNorm
            );
            Map<String, Object> effectiveHeader = captureStage == null
                    ? header
                    : captureStage.maybeDownscaleClientReferenceHeader(header, frame.cameraId());
            Map<String, Object> durableHeader = pinDurableReference(effectiveHeader, frame.cameraId());
            ReferenceSnapshot snapshot = new ReferenceSnapshot(snap.productType(), Map.copyOf(durableHeader));
            byCamera.put(frame.cameraId(), snapshot);
            String detectorId = detectorIdResolver == null ? "" : detectorIdResolver.apply(frame.cameraId());
            Map<String, Object> refHdr = BinaryInspectHeaders.setReferenceShmHeader(
                    snap.productType(), detectorId, durableHeader);
            for (BinaryRpcSupervisor python : AnalisSurfacePoolSupport.uniqueServerClients(pythonPool)) {
                python.command(refHdr);
            }
            log.info(
                    "pipeline reference from client cam={} bucket_group={} product_type={} frame_id={} shm={} source_shm={}",
                    frame.cameraId(),
                    bucketGroupId,
                    snap.productType(),
                    frame.frameId(),
                    durableHeader.get("shm_name"),
                    frame.shmName()
            );
        }
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

    private static int resolveBucketGroupId(ReferenceBundleSnapshot snap) {
        int minCameraId = snap.views().stream()
                .mapToInt(slot -> slot.frame().cameraId())
                .min()
                .orElse(0);
        return Math.max(0, minCameraId / CAMERAS_PER_BUCKET);
    }

    private static Map<String, Object> frameToCaptureHeader(
            ShmFrameRefData frame,
            ReferenceViewSlot slot,
            int jointCameraId,
            int bucketGroupId,
            Map<String, Object> bucketJointRoiNorm,
            List<Map<String, Object>> bucketJointPolygonNorm
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
        if (bucketJointPolygonNorm != null && bucketJointPolygonNorm.size() >= 3) {
            header.put("joint_roi_polygon_norm", bucketJointPolygonNorm);
        }
        return header;
    }

    private static Map<String, Object> normalizedRoi(PixelRoi roi, int frameWidth, int frameHeight) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("x", roi.x() / (double) frameWidth);
        normalized.put("y", roi.y() / (double) frameHeight);
        normalized.put("width", roi.width() / (double) frameWidth);
        normalized.put("height", roi.height() / (double) frameHeight);
        return Map.copyOf(normalized);
    }
}
