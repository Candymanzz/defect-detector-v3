package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.roi.InterestPolygonNormCodec;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPositioningExecutor;
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
        putCaptureAndReferenceShm(gHeader, cameraId, capture, activeReference);
        Object mainRoi = geometryCfg == null ? Map.of("x", 0, "y", 0, "width", 1224, "height", 1024) : geometryCfg.get("main_roi");
        Object mainRoiPolygon = resolveMainRoiPolygonNorm(activeReference, geometryCfg);
        if (mainRoiPolygon instanceof List<?> poly && poly.size() >= 3) {
            gHeader.put("mainRoiPolygonNorm", poly);
            int fw = YamlScalars.toInt(activeReference.header().get("width"), YamlScalars.toInt(capture.header().get("width"), 1224));
            int fh = YamlScalars.toInt(activeReference.header().get("height"), YamlScalars.toInt(capture.header().get("height"), 1024));
            @SuppressWarnings("unchecked")
            Map<String, Object> bbox = InterestPolygonNormCodec.boundingPixelRoi((List<Map<String, Object>>) poly, fw, fh);
            if (bbox != null) {
                mainRoi = bbox;
            }
        }
        gHeader.put("mainRoi", mainRoi);
        if (activeReference != null
                && activeReference.header() != null
                && YamlScalars.toBool(activeReference.header().get("client_reference_bundle"), false)) {
            gHeader.put("client_reference_bundle", true);
        }
        gHeader.put("jointRoi", resolveJointRoi(cameraId, activeReference, geometryCfg));
        gHeader.put("jointMode", resolveJointMode(cameraId, activeReference, gHeader.get("jointRoi")));
        gHeader.put("pixelsToMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("pixels_to_mm"), 0.02));
        gHeader.put("maxShiftMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_shift_mm"), 0.5));
        gHeader.put("maxRotationDeg", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_rotation_deg"), 1.0));
        gHeader.put("maxJointDefectMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_joint_defect_mm"), 0.5));
        gHeader.put("jointMinWidthMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("joint_min_width_mm"), 0.25));
        gHeader.put("jointMaxWidthMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("joint_max_width_mm"), 3.0));
        gHeader.put(
                "maxJointParallelismDeg",
                YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_joint_parallelism_deg"), 5.0)
        );
        gHeader.put(
                "maxJointTaperMm",
                YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_joint_taper_mm"), 0.8)
        );
        gHeader.put("jointSeamSegmentationEnabled", true);
        gHeader.put(
                "jointSeamSegmentationSensitivity",
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                YamlScalars.toDouble(
                                        geometryCfg == null
                                                ? null
                                                : geometryCfg.get("joint_seam_segmentation_sensitivity"),
                                        0.5
                                )
                        )
                )
        );
        Object jointPolygon = resolveJointRoiPolygonNorm(activeReference);
        if (jointPolygon instanceof List<?> poly && poly.size() >= 3) {
            gHeader.put("jointRoiPolygonNorm", poly);
        }
        double defaultThreshold = YamlScalars.toDouble(pythonCfg == null ? null : pythonCfg.get("fallback_threshold"), 0.25);
        double maxWrinkles = YamlScalars.toDouble(
                geometryCfg == null ? null : geometryCfg.get("max_wrinkles_score"),
                defaultThreshold
        );
        gHeader.put("threshold", defaultThreshold);
        gHeader.put("maxWrinklesScore", maxWrinkles);
        // Pose already locked by java-positioning — do not re-ORB/re-warp (destroys alignment).
        if (YamlScalars.toBool(capture.header().get(InspectPositioningExecutor.HEADER_ALIGNED), false)) {
            gHeader.put("pose_locked", true);
        }
        syncWrinklesRoiFromMainRoi(gHeader);
        return gHeader;
    }

    /**
     * Выравнивание позы ведра к эталону перед geometry / analisSurface.
     */
    public static Map<String, Object> positioningHeader(
            int cameraId,
            BinaryProtocol.Message capture,
            ReferenceSnapshot activeReference,
            Map<String, Object> geometryCfg,
            Map<String, Object> positioningCfg
    ) {
        Map<String, Object> pHeader = new HashMap<>();
        pHeader.put("op", "position_shm");
        putCaptureAndReferenceShm(pHeader, cameraId, capture, activeReference);
        Object mainRoi = geometryCfg == null ? Map.of("x", 0, "y", 0, "width", 1224, "height", 1024) : geometryCfg.get("main_roi");
        Object mainRoiPolygon = resolveMainRoiPolygonNorm(activeReference, geometryCfg);
        if (mainRoiPolygon instanceof List<?> poly && poly.size() >= 3) {
            pHeader.put("mainRoiPolygonNorm", poly);
            int fw = YamlScalars.toInt(activeReference.header().get("width"), YamlScalars.toInt(capture.header().get("width"), 1224));
            int fh = YamlScalars.toInt(activeReference.header().get("height"), YamlScalars.toInt(capture.header().get("height"), 1024));
            @SuppressWarnings("unchecked")
            Map<String, Object> bbox = InterestPolygonNormCodec.boundingPixelRoi((List<Map<String, Object>>) poly, fw, fh);
            if (bbox != null) {
                mainRoi = bbox;
            }
        }
        if (positioningCfg != null && positioningCfg.get("main_roi") != null) {
            mainRoi = positioningCfg.get("main_roi");
        }
        pHeader.put("mainRoi", mainRoi);
        double pixelsToMm = YamlScalars.toDouble(
                positioningCfg != null ? positioningCfg.get("pixels_to_mm") : null,
                YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("pixels_to_mm"), 0.02)
        );
        // Soft metrics only — large discrepancy must still produce an aligned frame.
        double maxShift = YamlScalars.toDouble(
                positioningCfg != null ? positioningCfg.get("max_shift_mm") : null,
                9999.0
        );
        double maxRot = YamlScalars.toDouble(
                positioningCfg != null ? positioningCfg.get("max_rotation_deg") : null,
                9999.0
        );
        pHeader.put("pixelsToMm", pixelsToMm);
        pHeader.put("maxShiftMm", maxShift);
        pHeader.put("maxRotationDeg", maxRot);
        pHeader.put("write_aligned", YamlScalars.toBool(positioningCfg == null ? null : positioningCfg.get("write_aligned"), true));
        pHeader.put("output_shm_name", "iml_pos_cam_" + cameraId);
        return pHeader;
    }

    private static void putCaptureAndReferenceShm(
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

    /**
     * Морщины проверяются в том же ROI, что передан для кадра (interest/main), не из YAML.
     */
    public static void syncWrinklesRoiFromMainRoi(Map<String, Object> gHeader) {
        Object mainRoi = gHeader.get("mainRoi");
        if (mainRoi != null) {
            gHeader.put("wrinklesRoi", mainRoi);
        }
    }

    /**
     * Client {@code reference_bundle} without joint ROI/polygon — geometry must not run
     * (YAML {@code joint_roi} fallback is intentionally ignored for client bundles).
     */
    public static boolean isClientReferenceWithoutJointRoi(ReferenceSnapshot activeReference) {
        if (activeReference == null || activeReference.header() == null) {
            return false;
        }
        if (!YamlScalars.toBool(activeReference.header().get("client_reference_bundle"), false)) {
            return false;
        }
        return !hasUsableClientJointRoi(activeReference.header());
    }

    public static boolean hasUsableClientJointRoi(Map<String, Object> referenceHeader) {
        if (referenceHeader == null || referenceHeader.isEmpty()) {
            return false;
        }
        Object poly = referenceHeader.get("joint_roi_polygon_norm");
        if (poly instanceof List<?> list && list.size() >= 3) {
            return true;
        }
        Object raw = referenceHeader.get("joint_roi_norm");
        if (!(raw instanceof Map<?, ?> normalized)) {
            return false;
        }
        double width = YamlScalars.toDouble(normalized.get("width"), 0d);
        double height = YamlScalars.toDouble(normalized.get("height"), 0d);
        return width > 0d && height > 0d;
    }

    private static Object resolveJointRoi(int cameraId, ReferenceSnapshot activeReference, Map<String, Object> geometryCfg) {
        if (activeReference != null && activeReference.header() != null) {
            // joint_roi_norm один на ведро: на joint-камере — full inspect, на остальных — visibility.
            Object raw = activeReference.header().get("joint_roi_norm");
            if (raw instanceof Map<?, ?> normalized) {
                int frameWidth = YamlScalars.toInt(activeReference.header().get("width"), 0);
                int frameHeight = YamlScalars.toInt(activeReference.header().get("height"), 0);
                if (frameWidth > 0 && frameHeight > 0) {
                    int x = (int) Math.round(YamlScalars.toDouble(normalized.get("x"), 0d) * frameWidth);
                    int y = (int) Math.round(YamlScalars.toDouble(normalized.get("y"), 0d) * frameHeight);
                    int width = (int) Math.round(YamlScalars.toDouble(normalized.get("width"), 0d) * frameWidth);
                    int height = (int) Math.round(YamlScalars.toDouble(normalized.get("height"), 0d) * frameHeight);
                    if (width > 0 && height > 0) {
                        return Map.of("x", x, "y", y, "width", width, "height", height);
                    }
                }
            }
            if (YamlScalars.toBool(activeReference.header().get("client_reference_bundle"), false)) {
                return null;
            }
        }
        return geometryCfg == null ? null : geometryCfg.get("joint_roi");
    }

    private static Object resolveJointRoiPolygonNorm(ReferenceSnapshot activeReference) {
        if (activeReference == null || activeReference.header() == null) {
            return null;
        }
        Object raw = activeReference.header().get("joint_roi_polygon_norm");
        if (raw instanceof List<?> poly && poly.size() >= 3) {
            return poly;
        }
        return null;
    }

    private static String resolveJointMode(int cameraId, ReferenceSnapshot activeReference, Object jointRoi) {
        if (jointRoi == null) {
            return "full";
        }
        if (activeReference != null && activeReference.header() != null) {
            int jointCameraId = YamlScalars.toInt(activeReference.header().get("joint_camera_id"), -1);
            if (jointCameraId >= 0 && cameraId != jointCameraId) {
                return "visibility";
            }
        }
        return "full";
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
                YamlScalars.toInt(capture.header().get("width"), 1224)
        );
        int fh = YamlScalars.toInt(
                activeReference != null && activeReference.header() != null ? activeReference.header().get("height") : null,
                YamlScalars.toInt(capture.header().get("height"), 1024)
        );
        Map<String, Object> bbox = InterestPolygonNormCodec.boundingPixelRoi((List<Map<String, Object>>) poly, fw, fh);
        if (bbox != null) {
            gHeader.put("mainRoi", bbox);
            syncWrinklesRoiFromMainRoi(gHeader);
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
        // Keep the threshold absent so Python can resolve default_threshold from the
        // selected analysis profile. GeometryRuntimeConfig may still add an explicit
        // per-frame override after this header is built.
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
        if (activeReference != null && activeReference.header() != null) {
            pyHeader.put("reference_shm_name", activeReference.header().get("shm_name"));
            pyHeader.put("reference_shm_offset", activeReference.header().get("shm_offset"));
            pyHeader.put("reference_width", activeReference.header().get("width"));
            pyHeader.put("reference_height", activeReference.header().get("height"));
            pyHeader.put("reference_stride", activeReference.header().get("stride"));
        }
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
