package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPositioningExecutor;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Заголовки python inspect_shm / set_reference_shm. */
final class BinaryPythonInspectHeaders {

    private BinaryPythonInspectHeaders() {
    }

    static Map<String, Object> pythonInspectHeader(
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
        if (YamlScalars.toBool(capture.header().get(InspectPositioningExecutor.HEADER_ALIGNED), false)) {
            pyHeader.put(
                    "alignment_h_ref_to_cur",
                    List.of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            );
        } else if (geomResp != null) {
            Object h = geomResp.header().get("homographyRefToCurrent");
            if (h != null) {
                pyHeader.put("alignment_h_ref_to_cur", h);
            }
        }
        return pyHeader;
    }

    static Map<String, Object> pythonInspectHeader(
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
        if (activeReference != null && activeReference.header() != null) {
            pyHeader.put("reference_shm_name", activeReference.header().get("shm_name"));
            pyHeader.put("reference_shm_offset", activeReference.header().get("shm_offset"));
            pyHeader.put("reference_width", activeReference.header().get("width"));
            pyHeader.put("reference_height", activeReference.header().get("height"));
            pyHeader.put("reference_stride", activeReference.header().get("stride"));
        }
        Object poly = BinaryInspectHeaderSupport.resolveMainRoiPolygonNorm(activeReference, null);
        if (poly != null) {
            pyHeader.put("roi_polygon_norm", poly);
        }
        return pyHeader;
    }

    static Map<String, Object> setReferenceShmHeader(
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
