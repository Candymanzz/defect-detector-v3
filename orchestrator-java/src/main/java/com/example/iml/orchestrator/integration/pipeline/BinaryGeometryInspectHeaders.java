package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.roi.InterestPolygonNormCodec;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPositioningExecutor;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Заголовки geometry / positioning binary inspect. */
final class BinaryGeometryInspectHeaders {

    private BinaryGeometryInspectHeaders() {
    }

    static Map<String, Object> geometryInspectHeader(
            int cameraId,
            BinaryProtocol.Message capture,
            ReferenceSnapshot activeReference,
            Map<String, Object> geometryCfg,
            Map<String, Object> pythonCfg
    ) {
        Map<String, Object> gHeader = new HashMap<>();
        gHeader.put("op", "inspect_shm");
        BinaryInspectHeaderSupport.putCaptureAndReferenceShm(gHeader, cameraId, capture, activeReference);
        Object mainRoi = geometryCfg == null
                ? Map.of("x", 0, "y", 0, "width", 1224, "height", 1024)
                : geometryCfg.get("main_roi");
        Object mainRoiPolygon = BinaryInspectHeaderSupport.resolveMainRoiPolygonNorm(activeReference, geometryCfg);
        if (mainRoiPolygon instanceof List<?> poly && poly.size() >= 3) {
            gHeader.put("mainRoiPolygonNorm", poly);
            int fw = YamlScalars.toInt(
                    activeReference.header().get("width"),
                    YamlScalars.toInt(capture.header().get("width"), 1224)
            );
            int fh = YamlScalars.toInt(
                    activeReference.header().get("height"),
                    YamlScalars.toInt(capture.header().get("height"), 1024)
            );
            Map<String, Object> bbox = InterestPolygonNormCodec.boundingPixelRoi(poly, fw, fh);
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
        gHeader.put("jointRoi", resolveJointRoi(activeReference, geometryCfg));
        gHeader.put("jointMode", resolveJointMode(cameraId, activeReference, gHeader.get("jointRoi")));
        gHeader.put("pixelsToMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("pixels_to_mm"), 0.02));
        gHeader.put("maxShiftMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_shift_mm"), 0.5));
        gHeader.put("maxRotationDeg", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_rotation_deg"), 1.0));
        gHeader.put("maxJointDefectMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_joint_defect_mm"), 0.3));
        gHeader.put("jointMinWidthMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("joint_min_width_mm"), 0.5));
        gHeader.put("jointMaxWidthMm", YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("joint_max_width_mm"), 3.0));
        gHeader.put(
                "maxJointParallelismDeg",
                YamlScalars.toDouble(geometryCfg == null ? null : geometryCfg.get("max_joint_parallelism_deg"), 3.0)
        );
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
        BinaryInspectHeaders.syncWrinklesRoiFromMainRoi(gHeader);
        return gHeader;
    }

    static Map<String, Object> positioningHeader(
            int cameraId,
            BinaryProtocol.Message capture,
            ReferenceSnapshot activeReference,
            Map<String, Object> geometryCfg,
            Map<String, Object> positioningCfg
    ) {
        Map<String, Object> pHeader = new HashMap<>();
        pHeader.put("op", "position_shm");
        BinaryInspectHeaderSupport.putCaptureAndReferenceShm(pHeader, cameraId, capture, activeReference);
        Object mainRoi = geometryCfg == null
                ? Map.of("x", 0, "y", 0, "width", 1224, "height", 1024)
                : geometryCfg.get("main_roi");
        Object mainRoiPolygon = BinaryInspectHeaderSupport.resolveMainRoiPolygonNorm(activeReference, geometryCfg);
        if (mainRoiPolygon instanceof List<?> poly && poly.size() >= 3) {
            pHeader.put("mainRoiPolygonNorm", poly);
            int fw = YamlScalars.toInt(
                    activeReference.header().get("width"),
                    YamlScalars.toInt(capture.header().get("width"), 1224)
            );
            int fh = YamlScalars.toInt(
                    activeReference.header().get("height"),
                    YamlScalars.toInt(capture.header().get("height"), 1024)
            );
            Map<String, Object> bbox = InterestPolygonNormCodec.boundingPixelRoi(poly, fw, fh);
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

    private static Object resolveJointRoi(ReferenceSnapshot activeReference, Map<String, Object> geometryCfg) {
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
}
