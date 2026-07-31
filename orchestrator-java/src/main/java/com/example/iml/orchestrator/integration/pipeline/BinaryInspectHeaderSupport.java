package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.pipeline.roi.InterestPolygonNormCodec;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.util.List;
import java.util.Map;

/** Shared helpers for binary inspect header builders. */
final class BinaryInspectHeaderSupport {

    private BinaryInspectHeaderSupport() {
    }

    static void putCaptureAndReferenceShm(
            Map<String, Object> header,
            int cameraId,
            BinaryProtocol.Message capture,
            ReferenceSnapshot activeReference
    ) {
        header.put("camera_id", cameraId);
        header.put("frame_id", capture.header().get("frame_id"));
        header.put("shm_name", capture.header().get("shm_name"));
        header.put("shm_offset", capture.header().get("shm_offset"));
        header.put("width", capture.header().get("width"));
        header.put("height", capture.header().get("height"));
        header.put("stride", capture.header().get("stride"));
        header.put("reference_shm_name", activeReference.header().get("shm_name"));
        header.put("reference_shm_offset", activeReference.header().get("shm_offset"));
        header.put("reference_width", activeReference.header().get("width"));
        header.put("reference_height", activeReference.header().get("height"));
        header.put("reference_stride", activeReference.header().get("stride"));
    }

    static Object resolveMainRoiPolygonNorm(ReferenceSnapshot activeReference, Map<String, Object> geometryCfg) {
        if (geometryCfg != null) {
            Object cfgPoly = geometryCfg.get("main_roi_polygon_norm");
            if (cfgPoly == null) {
                cfgPoly = geometryCfg.get("mainRoiPolygonNorm");
            }
            if (cfgPoly instanceof List<?> list && list.size() >= 3) {
                return list;
            }
        }
        if (activeReference != null && activeReference.header() != null) {
            Object refPoly = activeReference.header().get("interest_polygon_norm");
            if (refPoly instanceof List<?> list && list.size() >= 3) {
                return list;
            }
        }
        return null;
    }

    static void applyMainRoiFromPolygon(
            Map<String, Object> gHeader,
            BinaryProtocol.Message capture,
            ReferenceSnapshot activeReference
    ) {
        Object poly = gHeader.get("mainRoiPolygonNorm");
        if (!(poly instanceof List<?> list) || list.size() < 3) {
            return;
        }
        int fw = YamlScalars.toInt(
                activeReference != null && activeReference.header() != null
                        ? activeReference.header().get("width") : null,
                YamlScalars.toInt(capture.header().get("width"), 1224)
        );
        int fh = YamlScalars.toInt(
                activeReference != null && activeReference.header() != null
                        ? activeReference.header().get("height") : null,
                YamlScalars.toInt(capture.header().get("height"), 1024)
        );
        Map<String, Object> bbox = InterestPolygonNormCodec.boundingPixelRoi(list, fw, fh);
        if (bbox != null) {
            gHeader.put("mainRoi", bbox);
            BinaryInspectHeaders.syncWrinklesRoiFromMainRoi(gHeader);
        }
    }
}
