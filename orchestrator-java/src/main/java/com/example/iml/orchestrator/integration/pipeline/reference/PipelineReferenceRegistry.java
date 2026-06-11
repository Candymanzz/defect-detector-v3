package com.example.iml.orchestrator.integration.pipeline.reference;

import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceViewSlot;
import com.example.iml.orchestrator.integration.clientws.bundle.ShmFrameRefData;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.roi.InterestPolygonNormCodec;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Эталоны для пайплайна: из камеры ({@code reference_source=camera}) или от клиента по WS.
 */
public final class PipelineReferenceRegistry {

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
    // Detector synchronization succeeds before this method publishes the new local snapshot set.
    public void applyClientBundle(
            Logger log,
            ReferenceBundleSnapshot snap,
            Map<Integer, String> productTypeByCamera
    ) {
        Map<Integer, ReferenceSnapshot> pending = new LinkedHashMap<>();
        for (ReferenceViewSlot slot : snap.views()) {
            ShmFrameRefData frame = slot.frame();
            Map<String, Object> header = frameToCaptureHeader(frame, slot);
            String productType = productTypeByCamera.getOrDefault(frame.cameraId(), snap.productType());
            ReferenceSnapshot snapshot = new ReferenceSnapshot(productType, Map.copyOf(header));
            pending.put(frame.cameraId(), snapshot);
            log.info(
                    "pipeline reference from client cam={} product_type={} frame_id={} shm={}",
                    frame.cameraId(),
                    productType,
                    frame.frameId(),
                    frame.shmName()
            );
        }
        byCamera.putAll(pending);
    }

    private static Map<String, Object> frameToCaptureHeader(ShmFrameRefData frame, ReferenceViewSlot slot) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("camera_id", frame.cameraId());
        header.put("frame_id", frame.frameId());
        header.put("shm_name", frame.shmName());
        header.put("shm_offset", frame.shmOffset());
        header.put("width", frame.width());
        header.put("height", frame.height());
        header.put("stride", frame.strideBytes());
        if (frame.pixelFormat() != null && !frame.pixelFormat().isBlank()) {
            header.put("format", frame.pixelFormat());
        }
        List<java.util.Map<String, Object>> polygonNorm = slot.hasInterestPolygonNorm()
                ? InterestPolygonNormCodec.fromNormPoints(slot.interestPolygonNorm())
                : InterestPolygonNormCodec.fromPixelRoi(slot.interestRoi(), frame.width(), frame.height());
        if (polygonNorm.size() >= 3) {
            header.put("interest_polygon_norm", polygonNorm);
        }
        return header;
    }
}
