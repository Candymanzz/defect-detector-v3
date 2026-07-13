package com.example.iml.orchestrator.integration.pipeline.reference;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientws.bundle.PixelRoi;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceViewSlot;
import com.example.iml.orchestrator.integration.clientws.bundle.ShmFrameRefData;
import com.example.iml.orchestrator.integration.pipeline.BinaryInspectHeaders;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.roi.InterestPolygonNormCodec;
import com.example.iml.orchestrator.integration.pipeline.spi.CameraCaptureStage;
import com.example.iml.orchestrator.integration.python.AnalisSurfacePoolSupport;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * Эталоны для пайплайна: из камеры ({@code reference_source=camera}) или от клиента по WS.
 * После двух пакетов эталонов (ведро 0–4 и 5–9) хранит по одному {@link ReferenceSnapshot} на камеру,
 * как analisSurface хранит {@code references[product#cam=id]}.
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

        for (ReferenceViewSlot slot : snap.views()) {
            ShmFrameRefData frame = slot.frame();
            Map<String, Object> header = frameToCaptureHeader(
                    frame,
                    slot,
                    jointCameraId,
                    bucketGroupId,
                    bucketJointRoiNorm
            );
            Map<String, Object> effectiveHeader = captureStage == null
                    ? header
                    : captureStage.maybeDownscaleClientReferenceHeader(header, frame.cameraId());
            ReferenceSnapshot snapshot = new ReferenceSnapshot(snap.productType(), Map.copyOf(effectiveHeader));
            byCamera.put(frame.cameraId(), snapshot);
            String detectorId = detectorIdResolver == null ? "" : detectorIdResolver.apply(frame.cameraId());
            Map<String, Object> refHdr = BinaryInspectHeaders.setReferenceShmHeader(
                    snap.productType(), detectorId, effectiveHeader);
            for (BinaryRpcSupervisor python : AnalisSurfacePoolSupport.uniqueServerClients(pythonPool)) {
                python.command(refHdr);
            }
            log.info(
                    "pipeline reference from client cam={} bucket_group={} product_type={} frame_id={} shm={}",
                    frame.cameraId(),
                    bucketGroupId,
                    snap.productType(),
                    frame.frameId(),
                    frame.shmName()
            );
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

    private static Map<String, Object> normalizedRoi(PixelRoi roi, int frameWidth, int frameHeight) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("x", roi.x() / (double) frameWidth);
        normalized.put("y", roi.y() / (double) frameHeight);
        normalized.put("width", roi.width() / (double) frameWidth);
        normalized.put("height", roi.height() / (double) frameHeight);
        return Map.copyOf(normalized);
    }
}
