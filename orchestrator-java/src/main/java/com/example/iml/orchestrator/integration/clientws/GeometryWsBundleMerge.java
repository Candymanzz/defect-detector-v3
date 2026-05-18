package com.example.iml.orchestrator.integration.clientws;

import com.example.iml.orchestrator.integration.clientws.bundle.PixelRoi;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceViewSlot;
import com.example.iml.orchestrator.integration.clientws.bundle.ShmFrameRefData;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * Фаза 6: подстановка в заголовок {@code inspect_shm} для java-geometry полей {@code reference_shm_*}
 * из выбранного ракурса пакета и {@code jointRoi} из joint-слота (см. {@code docs/CLIENT_WS_CONTRACT_PHASE0.md} §12a).
 */
public final class GeometryWsBundleMerge {

    private GeometryWsBundleMerge() {
    }

    /**
     * @param geometryReferenceViewIndex индекс ракурса 0…4 для {@code reference_shm_*}; {@code -1} — взять {@code joint_view_index} из пакета
     */
    public static void applyCommittedBundleToGeometryHeader(
            ClientWsReferenceContext ctx,
            int pipelineCameraId,
            int geometryReferenceViewIndex,
            Map<String, Object> gHeader,
            Logger log
    ) {
        if (ctx == null || !ctx.hasCommittedBundle()) {
            return;
        }
        ReferenceBundleSnapshot snap = ctx.snapshot().orElse(null);
        if (snap == null) {
            return;
        }
        List<ReferenceViewSlot> views = snap.views();
        if (views == null || views.isEmpty()) {
            return;
        }
        int jointIdx = snap.jointViewIndex();
        int refSlot = geometryReferenceViewIndex >= 0 ? geometryReferenceViewIndex : jointIdx;
        if (refSlot < 0 || refSlot >= views.size()) {
            refSlot = Math.min(Math.max(0, jointIdx), views.size() - 1);
        }
        ShmFrameRefData ref = views.get(refSlot).frame();
        if (ref.cameraId() != pipelineCameraId) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "geometry WS merge skipped: reference view camera_id={} pipeline cam={}",
                        ref.cameraId(),
                        pipelineCameraId
                );
            }
            return;
        }
        gHeader.put("reference_shm_name", ref.shmName());
        gHeader.put("reference_shm_offset", ref.shmOffset());
        gHeader.put("reference_width", ref.width());
        gHeader.put("reference_height", ref.height());
        gHeader.put("reference_stride", ref.strideBytes());

        if (jointIdx >= 0 && jointIdx < views.size()) {
            ShmFrameRefData jointFrame = views.get(jointIdx).frame();
            if (jointFrame.cameraId() != pipelineCameraId) {
                if (log.isDebugEnabled()) {
                    log.debug(
                            "geometry WS merge: jointRoi skipped (joint view camera_id={} pipeline cam={})",
                            jointFrame.cameraId(),
                            pipelineCameraId
                    );
                }
            } else {
                PixelRoi jr = views.get(jointIdx).jointRoi();
                if (jr != null) {
                    gHeader.put("jointRoi", Map.of("x", jr.x(), "y", jr.y(), "width", jr.width(), "height", jr.height()));
                }
            }
        }
    }
}
