package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.roi.InterestPolygonNormCodec;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сборка заголовков бинарных команд inspect_shm / set_reference_shm для geometry и python.
 */
public final class BinaryInspectHeaders {

    private BinaryInspectHeaders() {
    }

    public static Map<String, Object> geometryInspectHeader(
            int cameraId,
            BinaryProtocol.Message capture,
            ReferenceSnapshot activeReference,
            Map<String, Object> geometryCfg,
            Map<String, Object> pythonCfg
    ) {
        Map<String, Object> gHeader = new HashMap<>();
        gHeader.put("op", "inspect_shm");
        gHeader.put("camera_id", cameraId);
        gHeader.put("frame_id", capture.header().get("frame_id"));
        gHeader.put("shm_name", capture.header().get("shm_name"));
        gHeader.put("shm_offset", capture.header().get("shm_offset"));
        gHeader.put("width", capture.header().get("width"));
        gHeader.put("height", capture.header().get("height"));
        gHeader.put("stride", capture.header().get("stride"));
        gHeader.put("reference_shm_name", activeReference.header().get("shm_name"));
        gHeader.put("reference_shm_offset", activeReference.header().get("shm_offset"));
        gHeader.put("reference_width", activeReference.header().get("width"));
        gHeader.put("reference_height", activeReference.header().get("height"));
        gHeader.put("reference_stride", activeReference.header().get("stride"));
        Object mainRoi = geometryCfg == null ? Map.of("x", 0, "y", 0, "width", 2448, "height", 2048) : geometryCfg.get("main_roi");
        Object mainRoiPolygon = resolveMainRoiPolygonNorm(activeReference, geometryCfg);
        if (mainRoiPolygon instanceof List<?> poly && poly.size() >= 3) {
            gHeader.put("mainRoiPolygonNorm", poly);
            int fw = YamlScalars.toInt(activeReference.header().get("width"), YamlScalars.toInt(capture.header().get("width"), 2448));
            int fh = YamlScalars.toInt(activeReference.header().get("height"), YamlScalars.toInt(capture.header().get("height"), 2048));
            @SuppressWarnings("unchecked")
            Map<String, Object> bbox = InterestPolygonNormCodec.boundingPixelRoi((List<Map<String, Object>>) poly, fw, fh);
            if (bbox != null) {
                mainRoi = bbox;
            }
        }
        gHeader.put("mainRoi", mainRoi);
        gHeader.put("jointRoi", geometryCfg == null ? null : geometryCfg.get("joint_roi"));
        gHeader.put("wrinklesRoi", geometryCfg == null ? null : geometryCfg.get("wrinkles_roi"));
        gHeader.put("pixelsToMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("pixels_to_mm"), 0.01));
        gHeader.put("maxShiftMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_shift_mm"), 0.5));
        gHeader.put("maxRotationDeg", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_rotation_deg"), 1.0));
        gHeader.put("maxConcentricityMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_concentricity_mm"), 0.2));
        gHeader.put("maxJointDefectMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_joint_defect_mm"), 0.3));
        double defaultThreshold = YamlScalars.toDouble(pythonCfg == null ? null : pythonCfg.get("fallback_threshold"), 0.25);
        double maxWrinkles = YamlScalars.toDouble(
                geometryCfg == null ? null : geometryCfg.get("max_wrinkles_score"),
                defaultThreshold
        );
        gHeader.put("threshold", defaultThreshold);
        gHeader.put("maxWrinklesScore", maxWrinkles);
        return gHeader;
    }

    /**
     * После runtime-override: пересчитать {@code mainRoi} по ограничивающему прямоугольнику полигона.
     */
    @SuppressWarnings("unchecked")
    public static void applyMainRoiFromPolygon(
            Map<String, Object> gHeader,
            BinaryProtocol.Message capture,
            ReferenceSnapshot activeReference
    ) {
        Object poly = gHeader.get("mainRoiPolygonNorm");
        if (!(poly instanceof List<?> list) || list.size() < 3) {
            return;
        }
        int fw = YamlScalars.toInt(
                activeReference != null && activeReference.header() != null ? activeReference.header().get("width") : null,
                YamlScalars.toInt(capture.header().get("width"), 2448)
        );
        int fh = YamlScalars.toInt(
                activeReference != null && activeReference.header() != null ? activeReference.header().get("height") : null,
                YamlScalars.toInt(capture.header().get("height"), 2048)
        );
        Map<String, Object> bbox = InterestPolygonNormCodec.boundingPixelRoi((List<Map<String, Object>>) poly, fw, fh);
        if (bbox != null) {
            gHeader.put("mainRoi", bbox);
        }
    }

    public static Map<String, Object> pythonInspectHeader(
            int cameraId,
            String productType,
            String detectorId,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message geomResp,
            Map<String, Object> pythonCfg,
            boolean includeVisuals
    ) {
        Map<String, Object> pyHeader = new HashMap<>();
        pyHeader.put("op", "inspect_shm");
        pyHeader.put("camera_id", cameraId);
        pyHeader.put("frame_id", capture.header().get("frame_id"));
        pyHeader.put("product_type", productType);
        pyHeader.put("detector_id", detectorId);
        pyHeader.put("threshold", YamlScalars.toDouble(pythonCfg == null ? null : pythonCfg.get("fallback_threshold"), 0.25));
        // Горячий путь: false; превью — {@link com.example.iml.orchestrator.integration.ui.UiArtifactsSidecar}.
        pyHeader.put("include_visuals", includeVisuals);
        if (pythonCfg != null && pythonCfg.get("rois") != null) {
            pyHeader.put("rois", pythonCfg.get("rois"));
        }
        pyHeader.put("shm_name", capture.header().get("shm_name"));
        pyHeader.put("shm_offset", capture.header().get("shm_offset"));
        pyHeader.put("width", capture.header().get("width"));
        pyHeader.put("height", capture.header().get("height"));
        pyHeader.put("stride", capture.header().get("stride"));
        if (geomResp != null) {
            Object h = geomResp.header().get("homographyRefToCurrent");
            if (h != null) {
                pyHeader.put("alignment_h_ref_to_cur", h);
            }
        }
        return pyHeader;
    }

    public static Map<String, Object> pythonInspectHeader(
            int cameraId,
            String productType,
            String detectorId,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message geomResp,
            Map<String, Object> pythonCfg,
            boolean includeVisuals,
            ReferenceSnapshot activeReference
    ) {
        Map<String, Object> pyHeader = pythonInspectHeader(
                cameraId, productType, detectorId, capture, geomResp, pythonCfg, includeVisuals);
        Object poly = resolveMainRoiPolygonNorm(activeReference, null);
        if (poly != null) {
            pyHeader.put("roi_polygon_norm", poly);
        }
        return pyHeader;
    }

    @SuppressWarnings("unchecked")
    private static Object resolveMainRoiPolygonNorm(ReferenceSnapshot activeReference, Map<String, Object> geometryCfg) {
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

    public static Map<String, Object> setReferenceShmHeader(
            String productType,
            String detectorId,
            Map<String, Object> referenceCaptureHeader
    ) {
        Map<String, Object> h = new HashMap<>();
        h.put("op", "set_reference_shm");
        h.put("product_type", productType);
        h.put("detector_id", detectorId);
        h.put("shm_name", referenceCaptureHeader.get("shm_name"));
        h.put("shm_offset", referenceCaptureHeader.get("shm_offset"));
        h.put("width", referenceCaptureHeader.get("width"));
        h.put("height", referenceCaptureHeader.get("height"));
        h.put("stride", referenceCaptureHeader.get("stride"));
        if (referenceCaptureHeader.get("camera_id") != null) {
            h.put("camera_id", referenceCaptureHeader.get("camera_id"));
        }
        return h;
    }
}
