package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.util.ArrayList;
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
        int frameW = YamlScalars.toInt(capture.header().get("width"), 0);
        int frameH = YamlScalars.toInt(capture.header().get("height"), 0);
        List<Map<String, Object>> roiPolyNorm = resolveRoiPolygonNormForPython(pythonCfg, frameW, frameH);
        if (roiPolyNorm != null && roiPolyNorm.size() >= 3) {
            pyHeader.put("roi_polygon_norm", roiPolyNorm);
        }
        if (geomResp != null) {
            Object h = geomResp.header().get("homographyRefToCurrent");
            if (h != null) {
                pyHeader.put("alignment_h_ref_to_cur", h);
            }
        }
        return pyHeader;
    }

    /**
     * Многоугольник ROI в нормализованных координатах кадра [0,1]×[0,1] (как ожидает Python {@code validate_polygon_points}).
     * Источники (по приоритету): {@code python_detector.roi_polygon_norm}; один полигон в {@code rois} с полем {@code points};
     * первый прямоугольный элемент {@code rois} (x,y,width,height) → четыре угла.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resolveRoiPolygonNormForPython(Map<String, Object> pythonCfg, int frameW, int frameH) {
        if (pythonCfg == null) {
            return null;
        }
        Object explicit = pythonCfg.get("roi_polygon_norm");
        if (explicit != null) {
            List<Map<String, Object>> parsed = parseNormPointList(explicit);
            if (parsed != null && parsed.size() >= 3) {
                return parsed;
            }
        }
        Object roisObj = pythonCfg.get("rois");
        if (roisObj instanceof Map<?, ?> one) {
            Object pts = one.get("points");
            List<Map<String, Object>> parsed = parseNormPointList(pts);
            if (parsed != null && parsed.size() >= 3) {
                return parsed;
            }
        }
        if (roisObj instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Map<String, Object> m = (Map<String, Object>) first;
            if (m.get("points") != null) {
                List<Map<String, Object>> parsed = parseNormPointList(m.get("points"));
                if (parsed != null && parsed.size() >= 3) {
                    return parsed;
                }
            }
            Integer x = YamlScalars.toInt(m.get("x"), Integer.MIN_VALUE);
            Integer y = YamlScalars.toInt(m.get("y"), Integer.MIN_VALUE);
            int w = YamlScalars.toInt(m.get("width"), 0);
            int h = YamlScalars.toInt(m.get("height"), 0);
            if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE && w > 0 && h > 0 && frameW >= 1 && frameH >= 1) {
                int x1 = x;
                int y1 = y;
                int x2 = x + w - 1;
                int y2 = y + h - 1;
                double dx = Math.max(1, frameW - 1);
                double dy = Math.max(1, frameH - 1);
                List<Map<String, Object>> quad = new ArrayList<>(4);
                quad.add(normPoint(x1, y1, dx, dy));
                quad.add(normPoint(x2, y1, dx, dy));
                quad.add(normPoint(x2, y2, dx, dy));
                quad.add(normPoint(x1, y2, dx, dy));
                return quad;
            }
        }
        return null;
    }

    private static Map<String, Object> normPoint(int px, int py, double denomX, double denomY) {
        double nx = clamp01(px / denomX);
        double ny = clamp01(py / denomY);
        return Map.of("x", nx, "y", ny);
    }

    private static double clamp01(double v) {
        if (v < 0d) {
            return 0d;
        }
        if (v > 1d) {
            return 1d;
        }
        return v;
    }

    private static List<Map<String, Object>> parseNormPointList(Object raw) {
        if (raw == null) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> pt) {
                    double x = YamlScalars.toDouble(pt.get("x"), Double.NaN);
                    double y = YamlScalars.toDouble(pt.get("y"), Double.NaN);
                    if (!Double.isNaN(x) && !Double.isNaN(y)) {
                        out.add(Map.of("x", clamp01(x), "y", clamp01(y)));
                    }
                } else if (o instanceof List<?> pair && pair.size() >= 2) {
                    double x = YamlScalars.toDouble(pair.get(0), Double.NaN);
                    double y = YamlScalars.toDouble(pair.get(1), Double.NaN);
                    if (!Double.isNaN(x) && !Double.isNaN(y)) {
                        out.add(Map.of("x", clamp01(x), "y", clamp01(y)));
                    }
                }
            }
        }
        return out.isEmpty() ? null : out;
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
