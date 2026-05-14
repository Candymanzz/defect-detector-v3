package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.util.HashMap;
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
            Map<String, Object> geometryCfg
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
        gHeader.put("mainRoi", geometryCfg == null ? Map.of("x", 0, "y", 0, "width", 2448, "height", 2048) : geometryCfg.get("main_roi"));
        gHeader.put("jointRoi", geometryCfg == null ? null : geometryCfg.get("joint_roi"));
        gHeader.put("wrinklesRoi", geometryCfg == null ? null : geometryCfg.get("wrinkles_roi"));
        gHeader.put("pixelsToMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("pixels_to_mm"), 0.01));
        gHeader.put("maxShiftMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_shift_mm"), 0.5));
        gHeader.put("maxRotationDeg", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_rotation_deg"), 1.0));
        gHeader.put("maxConcentricityMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_concentricity_mm"), 0.2));
        gHeader.put("maxJointDefectMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_joint_defect_mm"), 0.3));
        gHeader.put("maxWrinklesScore", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_wrinkles_score"), 0.05));
        return gHeader;
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

    public static Map<String, Object> setReferenceShmHeader(
            String productType,
            String detectorId,
            Map<String, Object> referenceCaptureHeader
    ) {
        return Map.of(
                "op", "set_reference_shm",
                "product_type", productType,
                "detector_id", detectorId,
                "shm_name", referenceCaptureHeader.get("shm_name"),
                "shm_offset", referenceCaptureHeader.get("shm_offset"),
                "width", referenceCaptureHeader.get("width"),
                "height", referenceCaptureHeader.get("height"),
                "stride", referenceCaptureHeader.get("stride")
        );
    }
}
