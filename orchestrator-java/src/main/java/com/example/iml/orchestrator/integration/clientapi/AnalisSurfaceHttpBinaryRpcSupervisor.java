package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Вызовы детектора FastAPI analisSurface по HTTP: те же {@code op}, что ожидает пайплайн,
 * ответы приводятся к {@link BinaryProtocol.Message} для совместимости с решением и телеметрией.
 */
public final class AnalisSurfaceHttpBinaryRpcSupervisor implements BinaryRpcSupervisor {

    private static final Logger LOG = LogManager.getLogger(AnalisSurfaceHttpBinaryRpcSupervisor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object REFERENCE_HEATMAP_LOCK = new Object();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final Set<String> ALGORITHM_PARAM_KEYS = Set.of(
            "mainRoi",
            "mainRoiPolygonNorm",
            "jointRoi",
            "wrinklesRoi",
            "pixelsToMm",
            "maxShiftMm",
            "maxRotationDeg",
            "maxConcentricityMm",
            "maxJointDefectMm",
            "maxWrinklesScore",
            "jointThreshold",
            "threshold",
            "main_roi",
            "main_roi_polygon_norm",
            "joint_roi",
            "wrinkles_roi",
            "pixels_to_mm",
            "max_shift_mm",
            "max_rotation_deg",
            "max_concentricity_mm",
            "max_joint_defect_mm",
            "max_wrinkles_score"
    );

    private final String name;
    private final String baseUrl;
    private final int commandTimeoutMs;
    private int restartCount;

    public AnalisSurfaceHttpBinaryRpcSupervisor(String name, String baseUrl, int commandTimeoutMs) {
        this.name = Objects.requireNonNull(name);
        String u = Objects.requireNonNull(baseUrl, "baseUrl").trim();
        this.baseUrl = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        this.commandTimeoutMs = Math.max(100, commandTimeoutMs);
    }

    @Override
    public String supervisorLabel() {
        return name;
    }

    @Override
    public int restartCount() {
        return restartCount;
    }

    @Override
    public void start() throws IOException {
        health();
    }

    @Override
    public void restart() throws IOException {
        restartCount++;
        start();
    }

    @Override
    public void close() {
        // нет локального процесса
    }

    @Override
    public BinaryProtocol.Message health() throws IOException {
        IOException last = null;
        for (String path : List.of("/detector/health", "/health")) {
            try {
                HttpResponse<byte[]> resp = httpGetRaw(path);
                if (resp.statusCode() / 100 == 2) {
                    Map<String, Object> h = readJson(resp.body());
                    return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, h, new byte[0]);
                }
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("health: no path succeeded") : last;
    }

    @Override
    public BinaryProtocol.Message command(Map<String, Object> header) throws IOException {
        try {
            return commandNoRetry(header);
        } catch (IOException first) {
            LOG.warn("{} command failed; retry once: {}", name, first.getMessage());
            restart();
            return commandNoRetry(header);
        }
    }

    @Override
    public BinaryProtocol.Message commandNoRetry(Map<String, Object> header) throws IOException {
        String op = String.valueOf(header.getOrDefault("op", ""));
        return switch (op) {
            case "stop" -> new BinaryProtocol.Message(
                    BinaryProtocol.MSG_RESPONSE,
                    Map.of("status", "ok", "service", "analis-surface-http"),
                    new byte[0]
            );
            case "health" -> health();
            case "set_reference_shm" -> uploadRefShm(header);
            case "inspect_shm" -> inspectShm(header);
            case "replace_fp_zones" -> replaceFpZones(header);
            case "sync_client_reference_bundle" -> syncClientReferenceBundle(header);
            case "set_active_reference_view" -> setActiveReferenceView(header);
            default -> new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", "unknown op=" + op + " (http transport)", "op", op),
                    new byte[0]
            );
        };
    }

    private BinaryProtocol.Message setActiveReferenceView(Map<String, Object> header) {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("product_type", String.valueOf(header.getOrDefault("product_type", "")));
        ok.put("view_index", YamlScalars.toInt(header.get("view_index"), 0));
        ok.put("transport", "http");
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, ok, new byte[0]);
    }

    /**
     * FastAPI хранит один эталон на {@code product_type}: загружаем активный ракурс из пакета и ROI.
     */
    private BinaryProtocol.Message syncClientReferenceBundle(Map<String, Object> header) throws IOException {
        String productType = String.valueOf(header.get("product_type"));
        int activeIdx = Math.max(0, Math.min(3, YamlScalars.toInt(header.get("active_reference_view_index"), 0)));
        Map<String, Object> view = findViewByIndex(header.get("views"), activeIdx);
        if (view == null) {
            return new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", "sync_client_reference_bundle: missing view index=" + activeIdx, "op", "sync_client_reference_bundle"),
                    new byte[0]
            );
        }
        Map<String, Object> refHdr = new LinkedHashMap<>(view);
        refHdr.put("product_type", productType);
        BinaryProtocol.Message refResp = uploadRefShm(refHdr);
        if (refResp.type() == BinaryProtocol.MSG_ERROR) {
            return refResp;
        }
        List<Map<String, Object>> points = findInterestPolygonNorm(header.get("interest_polygons_norm"), activeIdx);
        if (points == null || points.size() < 3) {
            Map<String, Object> interest = findInterestRoi(header.get("interest_rois"), activeIdx);
            if (interest != null) {
                int fw = YamlScalars.toInt(view.get("width"), 0);
                int fh = YamlScalars.toInt(view.get("height"), 0);
                if (fw > 1 && fh > 1) {
                    points = com.example.iml.orchestrator.integration.pipeline.roi.InterestPolygonNormCodec.fromPixelRoi(
                            new com.example.iml.orchestrator.integration.clientws.bundle.PixelRoi(
                                    YamlScalars.toInt(interest.get("x"), 0),
                                    YamlScalars.toInt(interest.get("y"), 0),
                                    YamlScalars.toInt(interest.get("width"), 0),
                                    YamlScalars.toInt(interest.get("height"), 0)
                            ),
                            fw,
                            fh
                    );
                }
            }
        }
        if (points != null && points.size() >= 3) {
            Map<String, Object> roiBody = new LinkedHashMap<>();
            roiBody.put("product_type", productType);
            roiBody.put("points", points);
            appendAlgorithmParams(roiBody, header);
            HttpResponse<byte[]> roiResp = httpPostJson("/roi-polygon", roiBody);
            if (roiResp.statusCode() / 100 != 2) {
                return errorMessageToMsg(roiResp, "roi-polygon");
            }
        }
        Object fp = header.get("fp_zones");
        if (fp instanceof List<?> list && !list.isEmpty()) {
            Map<String, Object> fpHdr = new LinkedHashMap<>(header);
            fpHdr.put("op", "replace_fp_zones");
            BinaryProtocol.Message fpResp = replaceFpZones(fpHdr);
            if (fpResp.type() == BinaryProtocol.MSG_ERROR) {
                return fpResp;
            }
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("product_type", productType);
        ok.put("active_reference_view_index", activeIdx);
        ok.put("transport", "http");
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, ok, new byte[0]);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findViewByIndex(Object viewsObj, int index) {
        if (!(viewsObj instanceof List<?> views)) {
            return null;
        }
        for (Object o : views) {
            if (o instanceof Map<?, ?> m) {
                int vi = YamlScalars.toInt(m.get("view_index"), -1);
                if (vi == index) {
                    return (Map<String, Object>) m;
                }
            }
        }
        if (index >= 0 && index < views.size() && views.get(index) instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> findInterestPolygonNorm(Object polysObj, int index) {
        if (!(polysObj instanceof List<?> polys)) {
            return null;
        }
        for (Object o : polys) {
            if (o instanceof Map<?, ?> m) {
                int vi = YamlScalars.toInt(m.get("view_index"), -1);
                if (vi == index) {
                    Object pts = m.get("points");
                    if (pts instanceof List<?> list) {
                        return normalizeRoiPoints(list);
                    }
                }
            }
        }
        return null;
    }

    private static Map<String, Object> findInterestRoi(Object roisObj, int index) {
        if (!(roisObj instanceof List<?> rois)) {
            return null;
        }
        for (Object o : rois) {
            if (o instanceof Map<?, ?> m) {
                int vi = YamlScalars.toInt(m.get("view_index"), -1);
                if (vi == index) {
                    return (Map<String, Object>) m;
                }
            }
        }
        return null;
    }

    private static List<Map<String, Object>> bboxToPolygonPoints(Map<String, Object> roi) {
        int x = YamlScalars.toInt(roi.get("x"), 0);
        int y = YamlScalars.toInt(roi.get("y"), 0);
        int w = YamlScalars.toInt(roi.get("width"), 0);
        int h = YamlScalars.toInt(roi.get("height"), 0);
        if (w <= 0 || h <= 0) {
            return List.of();
        }
        List<Map<String, Object>> pts = new ArrayList<>(4);
        pts.add(Map.of("x", (double) x, "y", (double) y));
        pts.add(Map.of("x", (double) (x + w), "y", (double) y));
        pts.add(Map.of("x", (double) (x + w), "y", (double) (y + h)));
        pts.add(Map.of("x", (double) x, "y", (double) (y + h)));
        return pts;
    }

    private BinaryProtocol.Message uploadRefShm(Map<String, Object> header) throws IOException {
        Map<String, Object> body = shmFrameJson(header);
        String invalid = validateRequiredShmFrameFields(body, "upload-ref-shm");
        if (invalid != null) {
            return new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", invalid, "op", "set_reference_shm"),
                    new byte[0]
            );
        }
        HttpResponse<byte[]> resp = httpPostJson("/upload-ref-shm", body);
        if (resp.statusCode() / 100 != 2) {
            throw new IOException(errorMessage("upload-ref-shm", resp));
        }
        Map<String, Object> h = readJson(resp.body());
        h.put("status", "ok");
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, h, new byte[0]);
    }

    private BinaryProtocol.Message inspectShm(Map<String, Object> header) throws IOException {
        synchronized (REFERENCE_HEATMAP_LOCK) {
            return inspectShmWithReference(header);
        }
    }

    private BinaryProtocol.Message inspectShmWithReference(Map<String, Object> header) throws IOException {
        BinaryProtocol.Message referenceResponse = uploadInspectionReference(header);
        if (referenceResponse != null && referenceResponse.type() == BinaryProtocol.MSG_ERROR) {
            return referenceResponse;
        }
        Object poly = header.get("roi_polygon_norm");
        if (poly instanceof List<?> list && list.size() >= 3) {
            String productType = String.valueOf(header.get("product_type"));
            List<Map<String, Object>> points = normalizeRoiPoints(list);
            if (points.size() >= 3) {
                Map<String, Object> roiBody = new LinkedHashMap<>();
                roiBody.put("product_type", productType);
                roiBody.put("points", points);
                appendAlgorithmParams(roiBody, header);
                HttpResponse<byte[]> roiResp = httpPostJson("/roi-polygon", roiBody);
                if (roiResp.statusCode() / 100 != 2) {
                    return errorMessageToMsg(roiResp, "roi-polygon");
                }
            }
        }
        Object heatmapOut = header.get("heatmap_u8_output_path");
        if (heatmapOut != null && !String.valueOf(heatmapOut).isBlank()) {
            return inspectShmVisuals(header);
        }
        Map<String, Object> body = shmFrameJson(header);
        String invalid = validateRequiredShmFrameFields(body, "inspect-shm");
        if (invalid != null) {
            return new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", invalid, "op", "inspect_shm"),
                    new byte[0]
            );
        }
        HttpResponse<byte[]> resp = httpPostJson("/inspect-shm", body);
        if (resp.statusCode() / 100 != 2) {
            return errorMessageToMsg(resp, "inspect-shm");
        }
        Map<String, Object> json = readJson(resp.body());
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, inspectJsonToStdioHeader(json), new byte[0]);
    }

    private BinaryProtocol.Message uploadInspectionReference(Map<String, Object> header) throws IOException {
        Object referenceShmName = header.get("reference_shm_name");
        if (referenceShmName == null || String.valueOf(referenceShmName).isBlank()) {
            return null;
        }

        Map<String, Object> referenceHeader = new LinkedHashMap<>();
        referenceHeader.put("product_type", header.get("product_type"));
        referenceHeader.put("detector_id", header.get("detector_id"));
        referenceHeader.put("camera_id", header.get("camera_id"));
        referenceHeader.put("shm_name", referenceShmName);
        referenceHeader.put("shm_offset", header.get("reference_shm_offset"));
        referenceHeader.put("width", header.get("reference_width"));
        referenceHeader.put("height", header.get("reference_height"));
        referenceHeader.put("stride", header.get("reference_stride"));
        return uploadRefShm(referenceHeader);
    }

    private BinaryProtocol.Message inspectShmVisuals(Map<String, Object> header) throws IOException {
        Map<String, Object> body = shmFrameJson(header);
        String invalid = validateRequiredShmFrameFields(body, "inspect-shm-visuals");
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
        HttpResponse<byte[]> resp = httpPostJson("/inspect-shm-visuals", body);
        if (resp.statusCode() / 100 != 2) {
            return errorMessageToMsg(resp, "inspect-shm-visuals");
        }
        Map<String, Object> json = readJson(resp.body());
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", "ok");
        h.put("product_type", String.valueOf(json.getOrDefault("product_type", header.get("product_type"))));
        h.put("detector_id", String.valueOf(json.getOrDefault("detector_id", header.get("detector_id"))));
        Object hm = json.get("heatmap_u8");
        if (hm instanceof Map<?, ?> hmMap) {
            h.put("heatmap_u8_path", hmMap.get("path"));
            h.put("heatmap_width", hmMap.get("width"));
            h.put("heatmap_height", hmMap.get("height"));
        }
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, h, new byte[0]);
    }

    private BinaryProtocol.Message replaceFpZones(Map<String, Object> header) throws IOException {
        String productType = String.valueOf(header.get("product_type"));
        int hw = YamlScalars.toInt(header.get("heatmap_width"), 0);
        int hh = YamlScalars.toInt(header.get("heatmap_height"), 0);
        if (hw <= 0 || hh <= 0) {
            return new BinaryProtocol.Message(
                    BinaryProtocol.MSG_ERROR,
                    Map.of("error", "replace_fp_zones: heatmap_width/height required", "op", "replace_fp_zones"),
                    new byte[0]
            );
        }
        HttpResponse<byte[]> listResp = httpGetRaw("/fp-zones/" + urlEncodePathSegment(productType));
        if (listResp.statusCode() / 100 != 2) {
            return errorMessageToMsg(listResp, "fp-zones list");
        }
        Map<String, Object> listJson = readJson(listResp.body());
        Object zonesObj = listJson.get("zones");
        if (zonesObj instanceof List<?> zones) {
            for (Object z : zones) {
                if (z instanceof Map<?, ?> zm) {
                    Object id = zm.get("id");
                    if (id != null) {
                        httpDeleteRaw("/fp-zones/" + urlEncodePathSegment(String.valueOf(id)));
                    }
                }
            }
        }
        Object fp = header.get("fp_zones");
        if (!(fp instanceof List<?> fpList)) {
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("status", "ok");
            ok.put("product_type", productType);
            ok.put("zones_count", 0);
            return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, ok, new byte[0]);
        }
        int added = 0;
        for (Object o : fpList) {
            if (!(o instanceof Map<?, ?> zone)) {
                continue;
            }
            List<Map<String, Object>> pts = heatmapPointsToRoiList(zone.get("points_norm_heatmap"));
            if (pts.size() < 3) {
                continue;
            }
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("product_type", productType);
            create.put("points", pts);
            create.put("heatmap_w", hw);
            create.put("heatmap_h", hh);
            Object note = zone.get("note");
            create.put("note", note == null ? "" : String.valueOf(note));
            HttpResponse<byte[]> cr = httpPostJson("/fp-zones", create);
            if (cr.statusCode() / 100 != 2) {
                return errorMessageToMsg(cr, "fp-zones create");
            }
            added++;
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("status", "ok");
        ok.put("product_type", productType);
        ok.put("zones_count", added);
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, ok, new byte[0]);
    }

    private static String urlEncodePathSegment(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static List<Map<String, Object>> heatmapPointsToRoiList(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                double x = YamlScalars.toDouble(m.get("x"), Double.NaN);
                double y = YamlScalars.toDouble(m.get("y"), Double.NaN);
                if (!Double.isNaN(x) && !Double.isNaN(y)) {
                    out.add(Map.of("x", x, "y", y));
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> normalizeRoiPoints(List<?> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                double x = YamlScalars.toDouble(m.get("x"), Double.NaN);
                double y = YamlScalars.toDouble(m.get("y"), Double.NaN);
                if (!Double.isNaN(x) && !Double.isNaN(y)) {
                    Map<String, Object> pt = new LinkedHashMap<>();
                    pt.put("x", x);
                    pt.put("y", y);
                    out.add(pt);
                }
            } else if (o instanceof List<?> pair && pair.size() >= 2) {
                double x = YamlScalars.toDouble(pair.get(0), Double.NaN);
                double y = YamlScalars.toDouble(pair.get(1), Double.NaN);
                if (!Double.isNaN(x) && !Double.isNaN(y)) {
                    Map<String, Object> pt = new LinkedHashMap<>();
                    pt.put("x", x);
                    pt.put("y", y);
                    out.add(pt);
                }
            }
        }
        return out;
    }

    private Map<String, Object> shmFrameJson(Map<String, Object> header) {
        Map<String, Object> body = new LinkedHashMap<>();
        copyIfPresent(body, header, "product_type");
        Object shmName = header.get("shm_name");
        if (shmName != null) {
            int cam = YamlScalars.toInt(header.get("camera_id"), -1);
            String logical = logicalShmNameForHttp(String.valueOf(shmName), cam);
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
        appendAlgorithmParams(body, header);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static void appendAlgorithmParams(Map<String, Object> body, Map<String, Object> header) {
        Map<String, Object> params = new LinkedHashMap<>();
        Object explicit = header.get("algorithm_params");
        if (explicit instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    params.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        for (String key : ALGORITHM_PARAM_KEYS) {
            Object value = header.get(key);
            if (value != null) {
                params.put(key, value);
            }
        }
        if (params.isEmpty()) {
            return;
        }
        body.put("algorithm_params", params);
        // Backward compatibility: existing FastAPI handlers may still read flat keys.
        Set<String> protectedKeys = new HashSet<>(Set.of(
                "product_type",
                "points",
                "shm_name",
                "width",
                "height",
                "stride",
                "shm_offset",
                "detector_id"
        ));
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!protectedKeys.contains(entry.getKey())) {
                body.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void copyIfPresent(Map<String, Object> to, Map<String, Object> from, String key) {
        if (from.containsKey(key) && from.get(key) != null) {
            to.put(key, from.get(key));
        }
    }

    /**
     * Для HTTP в analisSurface передаём короткое имя файла в iml_shm (без {@code D:\...}),
     * иначе uvicorn может отклонить запрос как «Invalid HTTP request received».
     */
    static String logicalShmNameForHttp(String shmName, int cameraId) {
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

    private Map<String, Object> inspectJsonToStdioHeader(Map<String, Object> json) {
        String status = String.valueOf(json.getOrDefault("status", "UNKNOWN"));
        boolean ok = !("БРАК".equalsIgnoreCase(status) || "FAIL".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status));
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

    private HttpResponse<byte[]> httpGetRaw(String path) throws IOException {
        URI uri = URI.create(baseUrl + path);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(commandTimeoutMs))
                .GET()
                .header("Accept", "application/json")
                .build();
        return send(req);
    }

    private void httpDeleteRaw(String path) throws IOException {
        URI uri = URI.create(baseUrl + path);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(commandTimeoutMs))
                .DELETE()
                .header("Accept", "application/json")
                .build();
        try {
            HTTP.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(name + " DELETE interrupted", e);
        }
    }

    private HttpResponse<byte[]> httpPostJson(String path, Map<String, Object> jsonBody) throws IOException {
        byte[] json = MAPPER.writeValueAsBytes(jsonBody);
        URI uri = URI.create(baseUrl + path);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(commandTimeoutMs))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        HttpResponse<byte[]> resp = send(req);
        if (resp.statusCode() / 100 != 2) {
            logHttpFailure(path, jsonBody, resp);
        }
        return resp;
    }

    private HttpResponse<byte[]> send(HttpRequest req) throws IOException {
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(name + " HTTP interrupted", e);
        }
    }

    private static String errorMessage(String ctx, HttpResponse<byte[]> resp) {
        String msg = ctx + " HTTP " + resp.statusCode();
        byte[] body = resp.body();
        if (body != null && body.length > 0) {
            try {
                Map<String, Object> err = MAPPER.readValue(body, new TypeReference<>() {});
                Object detail = err.get("detail");
                if (detail != null) {
                    return msg + ": " + detail;
                }
            } catch (Exception ignored) {
            }
            msg = msg + ": " + new String(body, StandardCharsets.UTF_8);
        }
        return msg;
    }

    private static BinaryProtocol.Message errorMessageToMsg(HttpResponse<byte[]> resp, String ctx) {
        String msg = errorMessage(ctx, resp);
        return new BinaryProtocol.Message(
                BinaryProtocol.MSG_ERROR,
                Map.of("error", msg, "http_status", resp.statusCode()),
                new byte[0]
        );
    }

    private static Map<String, Object> readJson(byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            return Map.of();
        }
        return MAPPER.readValue(body, new TypeReference<>() {});
    }

    private void logHttpFailure(String path, Map<String, Object> requestBody, HttpResponse<byte[]> resp) {
        String req = safeJson(requestBody, 3000);
        String body = safeResponseBody(resp.body(), 3000);
        LOG.warn(
                "{} HTTP POST {} failed status={} request={} response={}",
                name,
                path,
                resp.statusCode(),
                req,
                body
        );
    }

    private static String safeJson(Map<String, Object> body, int maxLen) {
        try {
            return truncate(MAPPER.writeValueAsString(body), maxLen);
        } catch (Exception e) {
            return "<json_serialize_failed:" + e.getMessage() + ">";
        }
    }

    private static String safeResponseBody(byte[] body, int maxLen) {
        if (body == null || body.length == 0) {
            return "";
        }
        return truncate(new String(body, StandardCharsets.UTF_8), maxLen);
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (maxLen <= 0 || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...";
    }

    private String validateRequiredShmFrameFields(Map<String, Object> body, String op) {
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
        String payload = safeJson(body, 1200);
        String msg = op + " skipped: missing/invalid required fields " + missing + " payload=" + payload;
        LOG.warn("{} {}", name, msg);
        return msg;
    }
}
