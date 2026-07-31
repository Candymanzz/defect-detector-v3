package com.example.iml.orchestrator.integration.pipeline.reference;

import com.example.iml.orchestrator.integration.pipeline.PipelineException;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceViewSlot;
import com.example.iml.orchestrator.integration.clientws.bundle.ShmFrameRefData;
import com.example.iml.orchestrator.integration.pipeline.BinaryInspectHeaders;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.spi.CameraCaptureStage;
import com.example.iml.orchestrator.integration.python.AnalisSurfacePoolSupport;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.List;
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
    ) throws PipelineException {
        int jointCameraId = snap.views().get(snap.jointViewIndex()).frame().cameraId();
        int bucketGroupId = PipelineReferencePinSupport.resolveBucketGroupId(snap);
        ReferenceViewSlot jointSlot = snap.views().get(snap.jointViewIndex());
        ShmFrameRefData jointFrame = jointSlot.frame();
        Map<String, Object> bucketJointRoiNorm = jointSlot.jointRoi() == null
                ? null
                : PipelineReferencePinSupport.normalizedRoi(jointSlot.jointRoi(), jointFrame.width(), jointFrame.height());

        for (ReferenceViewSlot slot : snap.views()) {
            ShmFrameRefData frame = slot.frame();
            Map<String, Object> header = PipelineReferencePinSupport.frameToCaptureHeader(
                    frame,
                    slot,
                    jointCameraId,
                    bucketGroupId,
                    bucketJointRoiNorm
            );
            Map<String, Object> effectiveHeader = captureStage == null
                    ? header
                    : captureStage.maybeDownscaleClientReferenceHeader(header, frame.cameraId());
            Map<String, Object> durableHeader;
            try {
                durableHeader = PipelineReferencePinSupport.pinDurableReference(effectiveHeader, frame.cameraId());
            } catch (IOException e) {
                throw new PipelineException("Failed to pin durable reference for camera " + frame.cameraId(), e);
            }
            ReferenceSnapshot snapshot = new ReferenceSnapshot(snap.productType(), Map.copyOf(durableHeader));
            byCamera.put(frame.cameraId(), snapshot);
            String detectorId = detectorIdResolver == null ? "" : detectorIdResolver.apply(frame.cameraId());
            Map<String, Object> refHdr = BinaryInspectHeaders.setReferenceShmHeader(
                    snap.productType(), detectorId, durableHeader);
            for (BinaryRpcSupervisor python : AnalisSurfacePoolSupport.uniqueServerClients(pythonPool)) {
                try {
                    python.command(refHdr);
                } catch (IOException e) {
                    throw new PipelineException("Failed to set_reference_shm for camera " + frame.cameraId(), e);
                }
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
}
