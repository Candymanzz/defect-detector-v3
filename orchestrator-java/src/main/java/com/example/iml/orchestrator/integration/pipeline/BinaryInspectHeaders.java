package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.protocol.BinaryProtocol;

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
        return BinaryGeometryInspectHeaders.geometryInspectHeader(
                cameraId, capture, activeReference, geometryCfg, pythonCfg);
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
        return BinaryGeometryInspectHeaders.positioningHeader(
                cameraId, capture, activeReference, geometryCfg, positioningCfg);
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
     * После runtime-override: пересчитать {@code mainRoi} по ограничивающему прямоугольнику полигона.
     */
    public static void applyMainRoiFromPolygon(
            Map<String, Object> gHeader,
            BinaryProtocol.Message capture,
            ReferenceSnapshot activeReference
    ) {
        BinaryInspectHeaderSupport.applyMainRoiFromPolygon(gHeader, capture, activeReference);
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
        return BinaryPythonInspectHeaders.pythonInspectHeader(
                cameraId, productType, detectorId, capture, geomResp, pythonCfg, includeVisuals);
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
        return BinaryPythonInspectHeaders.pythonInspectHeader(
                cameraId, productType, detectorId, capture, geomResp, pythonCfg, includeVisuals, activeReference);
    }

    public static Map<String, Object> setReferenceShmHeader(
            String productType,
            String detectorId,
            Map<String, Object> referenceCaptureHeader
    ) {
        return BinaryPythonInspectHeaders.setReferenceShmHeader(productType, detectorId, referenceCaptureHeader);
    }
}
