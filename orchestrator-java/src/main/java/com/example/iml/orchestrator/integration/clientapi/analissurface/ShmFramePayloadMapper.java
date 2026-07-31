package com.example.iml.orchestrator.integration.clientapi.analissurface;

import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Build / normalize HTTP payloads for SHM frame ops and ROI points. */
public final class ShmFramePayloadMapper {

    private static final Logger LOG = LogManager.getLogger(ShmFramePayloadMapper.class);

    private final String supervisorName;

    public ShmFramePayloadMapper(String supervisorName) {
        this.supervisorName = supervisorName;
    }

    public Map<String, Object> shmFrameJson(Map<String, Object> header) {
        Map<String, Object> body = new LinkedHashMap<>();
        String productType = String.valueOf(header.getOrDefault("product_type", ""));
        int cameraId = YamlScalars.toInt(header.get("camera_id"), -1);
        body.put("product_type", ReferenceRoiSignatureCache.scopedProductType(productType, cameraId));
        Object shmName = header.get("shm_name");
        if (shmName != null) {
            String logical = logicalShmNameForHttp(String.valueOf(shmName), cameraId);
            body.put("shm_name", logical);
        }
        copyIfPresent(body, header, "width");
        copyIfPresent(body, header, "height");
        copyIfPresent(body, header, "stride");
        copyIfPresent(body, header, "shm_offset");
        if (header.get("threshold") != null) {
            body.put("threshold", header.get("threshold"));
        }
        if (header.get("detector_id") != null) {
            body.put("detector_id", header.get("detector_id"));
        }
        copyIfPresent(body, header, "alignment_h_ref_to_cur");
        appendAlgorithmParams(body, header);
        return body;
    }

    public static void appendAlgorithmParams(Map<String, Object> body, Map<String, Object> header) {
        ShmFrameAlgorithmParams.appendAlgorithmParams(body, header);
    }

    public static void copyIfPresent(Map<String, Object> to, Map<String, Object> from, String key) {
        if (from.containsKey(key) && from.get(key) != null) {
            to.put(key, from.get(key));
        }
    }

    /**
     * Для HTTP в analisSurface передаём короткое имя файла в iml_shm (без {@code D:\...}),
     * иначе uvicorn может отклонить запрос как «Invalid HTTP request received».
     */
    public static String logicalShmNameForHttp(String shmName, int cameraId) {
        Path resolved = FrameJpegWriter.resolveShmPath(shmName, cameraId);
        if (resolved != null) {
            String base = resolved.getFileName().toString();
            if (base.endsWith(".bin")) {
                return base.substring(0, base.length() - 4);
            }
            return base;
        }
        String s = shmName.trim();
        if (s.contains("/") || s.contains("\\")) {
            int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
            s = s.substring(slash + 1);
        }
        if (s.endsWith(".bin")) {
            s = s.substring(0, s.length() - 4);
        }
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.isEmpty() && cameraId >= 0) {
            return "iml_cam_" + cameraId + "_frame";
        }
        return s;
    }

    public Map<String, Object> inspectJsonToStdioHeader(Map<String, Object> json) {
        String status = String.valueOf(json.getOrDefault("status", "UNKNOWN"));
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        boolean ok = !(
                "\u0411\u0420\u0410\u041a".equals(normalizedStatus)
                        || "FAIL".equals(normalizedStatus)
                        || "ERROR".equals(normalizedStatus)
                        || "REJECT".equals(normalizedStatus)
        );
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", status);
        h.put("ok", ok);
        h.put("anomaly_score", YamlScalars.toDouble(json.get("anomaly_score"), 0.0));
        h.put("threshold", YamlScalars.toDouble(json.get("threshold"), 0.0));
        h.put("product_type", String.valueOf(json.getOrDefault("product_type", "")));
        h.put("detector_id", String.valueOf(json.getOrDefault("detector_id", "")));
        h.put("raw_anomaly_score", YamlScalars.toDouble(json.get("raw_anomaly_score"), 0.0));
        h.put("rechecked_zones_count", YamlScalars.toInt(json.get("rechecked_zones_count"), 0));
        h.put("recheck_adjustment", YamlScalars.toDouble(json.get("recheck_adjustment"), 0.0));
        Object ids = json.get("rechecked_zone_ids");
        h.put("rechecked_zone_ids", ids == null ? List.of() : ids);
        return h;
    }

    public static List<Map<String, Object>> normalizeRoiPoints(List<?> list) {
        return ShmFrameRoiMapper.normalizeRoiPoints(list);
    }

    public static List<Map<String, Object>> heatmapPointsToRoiList(Object raw) {
        return ShmFrameRoiMapper.heatmapPointsToRoiList(raw);
    }

    public String validateRequiredShmFrameFields(Map<String, Object> body, String op) {
        List<String> missing = new ArrayList<>();
        String productType = String.valueOf(body.getOrDefault("product_type", "")).trim();
        String shmName = String.valueOf(body.getOrDefault("shm_name", "")).trim();
        int width = YamlScalars.toInt(body.get("width"), 0);
        int height = YamlScalars.toInt(body.get("height"), 0);
        if (productType.isEmpty()) {
            missing.add("product_type");
        }
        if (shmName.isEmpty()) {
            missing.add("shm_name");
        }
        if (width <= 0) {
            missing.add("width");
        }
        if (height <= 0) {
            missing.add("height");
        }
        if (missing.isEmpty()) {
            return null;
        }
        String payload = AnalisSurfaceHttpTransport.safeJson(body, 1200);
        String msg = op + " skipped: missing/invalid required fields " + missing + " payload=" + payload;
        LOG.warn("{} {}", supervisorName, msg);
        return msg;
    }
}
