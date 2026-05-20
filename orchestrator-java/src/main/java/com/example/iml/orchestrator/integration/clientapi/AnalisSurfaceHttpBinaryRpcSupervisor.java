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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Вызовы детектора FastAPI analisSurface по HTTP: те же {@code op}, что ожидает пайплайн,
 * ответы приводятся к {@link BinaryProtocol.Message} для совместимости с решением и телеметрией.
 */
public final class AnalisSurfaceHttpBinaryRpcSupervisor implements BinaryRpcSupervisor {

    private static final Logger LOG = LogManager.getLogger(AnalisSurfaceHttpBinaryRpcSupervisor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

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
        int activeIdx = Math.max(0, Math.min(4, YamlScalars.toInt(header.get("active_reference_view_index"), 0)));
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
        Map<String, Object> interest = findInterestRoi(header.get("interest_rois"), activeIdx);
        if (interest != null) {
            List<Map<String, Object>> points = bboxToPolygonPoints(interest);
            if (points.size() >= 3) {
                Map<String, Object> roiBody = new LinkedHashMap<>();
                roiBody.put("product_type", productType);
                roiBody.put("points", points);
                HttpResponse<byte[]> roiResp = httpPostJson("/roi-polygon", roiBody);
                if (roiResp.statusCode() / 100 != 2) {
                    return errorMessageToMsg(roiResp, "roi-polygon");
                }
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
        HttpResponse<byte[]> resp = httpPostJson("/upload-ref-shm", body);
        if (resp.statusCode() / 100 != 2) {
            throw new IOException(errorMessage("upload-ref-shm", resp));
        }
        Map<String, Object> h = readJson(resp.body());
        h.put("status", "ok");
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, h, new byte[0]);
    }

    private BinaryProtocol.Message inspectShm(Map<String, Object> header) throws IOException {
        Object heatmapOut = header.get("heatmap_u8_output_path");
        if (heatmapOut != null && !String.valueOf(heatmapOut).isBlank()) {
            return inspectShmVisuals(header);
        }
        Object poly = header.get("roi_polygon_norm");
        if (poly instanceof List<?> list && list.size() >= 3) {
            String productType = String.valueOf(header.get("product_type"));
            List<Map<String, Object>> points = normalizeRoiPoints(list);
            if (points.size() >= 3) {
                Map<String, Object> roiBody = new LinkedHashMap<>();
                roiBody.put("product_type", productType);
                roiBody.put("points", points);
                HttpResponse<byte[]> roiResp = httpPostJson("/roi-polygon", roiBody);
                if (roiResp.statusCode() / 100 != 2) {
                    return errorMessageToMsg(roiResp, "roi-polygon");
                }
            }
        }
        Map<String, Object> body = shmFrameJson(header);
        HttpResponse<byte[]> resp = httpPostJson("/inspect-shm", body);
        if (resp.statusCode() / 100 != 2) {
            return errorMessageToMsg(resp, "inspect-shm");
        }
        Map<String, Object> json = readJson(resp.body());
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, inspectJsonToStdioHeader(json), new byte[0]);
    }

    private BinaryProtocol.Message inspectShmVisuals(Map<String, Object> header) throws IOException {
        Map<String, Object> body = shmFrameJson(header);
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
        return body;
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
        return send(req);
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
}
