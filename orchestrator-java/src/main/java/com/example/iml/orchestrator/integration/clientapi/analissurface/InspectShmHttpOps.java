package com.example.iml.orchestrator.integration.clientapi.analissurface;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/** Reference upload, ROI ensure, inspect-shm / inspect-shm-visuals. */
public final class InspectShmHttpOps {

    private final AnalisSurfaceHttpTransport http;
    private final ReferenceRoiSignatureCache cache;
    private final ShmFramePayloadMapper payloads;
    private final InspectShmReferenceOps referenceOps;

    public InspectShmHttpOps(
            AnalisSurfaceHttpTransport http,
            ReferenceRoiSignatureCache cache,
            ShmFramePayloadMapper payloads
    ) {
        this.http = http;
        this.cache = cache;
        this.payloads = payloads;
        this.referenceOps = new InspectShmReferenceOps(http, cache, this);
    }

    public BinaryProtocol.Message uploadRefShm(Map<String, Object> header) throws IOException {
        Map<String, Object> body = payloads.shmFrameJson(header);
        String invalid = payloads.validateRequiredShmFrameFields(body, "upload-ref-shm");
        if (invalid != null) {
            return new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", invalid, "op", "set_reference_shm"),
                    new byte[0]
            );
        }
        HttpResponse<byte[]> resp = http.httpPostJson("/upload-ref-shm", body);
        if (resp.statusCode() / 100 != 2) {
            throw new IOException(AnalisSurfaceHttpTransport.errorMessage("upload-ref-shm", resp));
        }
        Map<String, Object> h = AnalisSurfaceHttpTransport.readJson(resp.body());
        h.put("status", "ok");
        cache.rememberReferenceSignature(header);
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, h, new byte[0]);
    }

    public BinaryProtocol.Message inspectShm(Map<String, Object> header) throws IOException {
        return inspectShmWithReference(header);
    }

    public BinaryProtocol.Message inspectShmWithReference(Map<String, Object> header) throws IOException {
        BinaryProtocol.Message referenceResponse = uploadInspectionReference(header);
        if (referenceResponse != null && referenceResponse.type() == BinaryProtocol.MSG_ERROR) {
            return referenceResponse;
        }
        int cameraId = YamlScalars.toInt(header.get("camera_id"), -1);
        String originalProductType = String.valueOf(header.get("product_type"));
        String scopedProductType = ReferenceRoiSignatureCache.scopedProductType(originalProductType, cameraId);
        Object poly = header.get("roi_polygon_norm");
        if (poly instanceof List<?> list && list.size() >= 3) {
            List<Map<String, Object>> points = ShmFramePayloadMapper.normalizeRoiPoints(list);
            if (points.size() >= 3) {
                BinaryProtocol.Message roiResp = ensureRoiPolygon(
                        originalProductType,
                        cameraId,
                        scopedProductType,
                        points,
                        header
                );
                if (roiResp != null && roiResp.type() == BinaryProtocol.MSG_ERROR) {
                    return roiResp;
                }
            }
        }
        Object heatmapOut = header.get("heatmap_u8_output_path");
        if (heatmapOut != null && !String.valueOf(heatmapOut).isBlank()) {
            synchronized (cache.scopeLock("heatmap:" + String.valueOf(heatmapOut).trim())) {
                return inspectShmVisuals(header);
            }
        }
        Map<String, Object> body = payloads.shmFrameJson(header);
        String invalid = payloads.validateRequiredShmFrameFields(body, "inspect-shm");
        if (invalid != null) {
            return new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", invalid, "op", "inspect_shm"),
                    new byte[0]
            );
        }
        HttpResponse<byte[]> resp = http.httpPostJson("/inspect-shm", body);
        if (resp.statusCode() / 100 != 2) {
            return AnalisSurfaceHttpTransport.errorMessageToMsg(resp, "inspect-shm");
        }
        Map<String, Object> json = AnalisSurfaceHttpTransport.readJson(resp.body());
        Map<String, Object> pyHeader = payloads.inspectJsonToStdioHeader(json);
        pyHeader.put("product_type", originalProductType);
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, pyHeader, new byte[0]);
    }

    public BinaryProtocol.Message uploadInspectionReference(Map<String, Object> header) throws IOException {
        return referenceOps.uploadInspectionReference(header);
    }

    public BinaryProtocol.Message ensureRoiPolygon(
            String productType,
            int cameraId,
            String scopedProductType,
            List<Map<String, Object>> points,
            Map<String, Object> header
    ) throws IOException {
        return referenceOps.ensureRoiPolygon(productType, cameraId, scopedProductType, points, header);
    }

    public BinaryProtocol.Message inspectShmVisuals(Map<String, Object> header) throws IOException {
        Map<String, Object> body = payloads.shmFrameJson(header);
        String invalid = payloads.validateRequiredShmFrameFields(body, "inspect-shm-visuals");
        if (invalid != null) {
            return new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", invalid, "op", "inspect_shm"),
                    new byte[0]
            );
        }
        Object heatmapPath = header.get("heatmap_u8_output_path");
        if (heatmapPath != null) {
            body.put("heatmap_u8_output_path", String.valueOf(heatmapPath));
        }
        ShmFramePayloadMapper.copyIfPresent(body, header, "heatmap_max_width");
        HttpResponse<byte[]> resp = http.httpPostJson("/inspect-shm-visuals", body);
        if (resp.statusCode() / 100 != 2) {
            return AnalisSurfaceHttpTransport.errorMessageToMsg(resp, "inspect-shm-visuals");
        }
        Map<String, Object> json = AnalisSurfaceHttpTransport.readJson(resp.body());
        Map<String, Object> h = payloads.inspectJsonToStdioHeader(json);
        h.put("product_type", String.valueOf(header.getOrDefault("product_type", "")));
        Object hm = json.get("heatmap_u8");
        if (hm instanceof Map<?, ?> hmMap) {
            h.put("heatmap_u8_path", hmMap.get("path"));
            h.put("heatmap_width", hmMap.get("width"));
            h.put("heatmap_height", hmMap.get("height"));
        }
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, h, new byte[0]);
    }
}
