package com.example.iml.orchestrator.integration.clientapi.analissurface;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reference upload + ROI ensure for inspect-shm flows. */
final class InspectShmReferenceOps {

    private final AnalisSurfaceHttpTransport http;
    private final ReferenceRoiSignatureCache cache;
    private final InspectShmHttpOps uploadOps;

    InspectShmReferenceOps(
            AnalisSurfaceHttpTransport http,
            ReferenceRoiSignatureCache cache,
            InspectShmHttpOps uploadOps
    ) {
        this.http = http;
        this.cache = cache;
        this.uploadOps = uploadOps;
    }

    BinaryProtocol.Message uploadInspectionReference(Map<String, Object> header) throws IOException {
        Object referenceShmName = header.get("reference_shm_name");
        if (referenceShmName == null || String.valueOf(referenceShmName).isBlank()) {
            return null;
        }
        String productType = String.valueOf(header.get("product_type"));
        int cameraId = YamlScalars.toInt(header.get("camera_id"), -1);
        String scopedProductType = ReferenceRoiSignatureCache.scopedProductType(productType, cameraId);
        String expectedSignature = ReferenceRoiSignatureCache.referenceSignature(
                String.valueOf(referenceShmName),
                header.get("reference_shm_offset"),
                header.get("reference_width"),
                header.get("reference_height"),
                header.get("reference_stride"),
                ReferenceRoiSignatureCache.referenceContentFingerprint(
                        header, String.valueOf(referenceShmName), cameraId)
        );
        String cacheKey = ReferenceRoiSignatureCache.runtimeKey(productType, cameraId);
        if (expectedSignature.equals(cache.getReferenceSignature(cacheKey))) {
            return null;
        }
        synchronized (cache.scopeLock(cacheKey)) {
            if (expectedSignature.equals(cache.getReferenceSignature(cacheKey))) {
                return null;
            }
            Map<String, Object> referenceHeader = new LinkedHashMap<>();
            referenceHeader.put("product_type", scopedProductType);
            referenceHeader.put("detector_id", header.get("detector_id"));
            referenceHeader.put("camera_id", cameraId);
            referenceHeader.put("shm_name", referenceShmName);
            referenceHeader.put("shm_offset", header.get("reference_shm_offset"));
            referenceHeader.put("width", header.get("reference_width"));
            referenceHeader.put("height", header.get("reference_height"));
            referenceHeader.put("stride", header.get("reference_stride"));
            BinaryProtocol.Message response = uploadOps.uploadRefShm(referenceHeader);
            if (response.type() != BinaryProtocol.MSG_ERROR) {
                cache.putReferenceSignature(cacheKey, expectedSignature);
            }
            return response;
        }
    }

    BinaryProtocol.Message ensureRoiPolygon(
            String productType,
            int cameraId,
            String scopedProductType,
            List<Map<String, Object>> points,
            Map<String, Object> header
    ) throws IOException {
        if (points == null || points.size() < 3) {
            return null;
        }
        String roiKey = ReferenceRoiSignatureCache.runtimeKey(productType, cameraId);
        String signature = cache.roiSignature(points);
        if (signature.equals(cache.getRoiSignature(roiKey))) {
            return null;
        }
        synchronized (cache.scopeLock("roi:" + roiKey)) {
            if (signature.equals(cache.getRoiSignature(roiKey))) {
                return null;
            }
            Map<String, Object> roiBody = new LinkedHashMap<>();
            roiBody.put("product_type", scopedProductType);
            roiBody.put("points", points);
            ShmFramePayloadMapper.appendAlgorithmParams(roiBody, header);
            HttpResponse<byte[]> roiResp = http.httpPostJson("/roi-polygon", roiBody);
            if (roiResp.statusCode() / 100 != 2) {
                return AnalisSurfaceHttpTransport.errorMessageToMsg(roiResp, "roi-polygon");
            }
            cache.putRoiSignature(roiKey, signature);
            return null;
        }
    }
}
