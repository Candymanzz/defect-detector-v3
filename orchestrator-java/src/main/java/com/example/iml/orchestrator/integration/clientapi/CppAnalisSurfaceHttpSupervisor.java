package com.example.iml.orchestrator.integration.clientapi;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.CameraAnalysisProfiles;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.example.iml.orchestrator.integration.logging.PerformanceTrace;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * HTTP-клиент нового C++ сервиса анализа поверхности (cppAnalisSurface).
 * <p>
 * Изолирован от Python-клиента {@link AnalisSurfaceHttpBinaryRpcSupervisor}: говорит только на контракте
 * cppAnalisSurface (эталон + ROI + настройки инспекции), без обучения, FP-зон, перспективной линии,
 * algorithm_params и профилей Simple/Pro. Операции ({@code op}) те же, что ожидает пайплайн, ответы приводятся к
 * {@link BinaryProtocol.Message}.
 * <p>
 * Каждый запрос инспекции самодостаточен: ROI (нормализованный, 0..1) отправляется в теле запроса, а при
 * {@code no_reference} (например, после рестарта сервиса) эталон загружается заново и запрос повторяется.
 */
public final class CppAnalisSurfaceHttpSupervisor implements BinaryRpcSupervisor {

    private static final Logger LOG = LogManager.getLogger(CppAnalisSurfaceHttpSupervisor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    /** Подписи уже загруженных эталонов (общие для всех клиентов пула одного процесса). */
    private static final ConcurrentHashMap<String, String> REFERENCE_SIGNATURES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> SCOPE_LOCKS = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final String NO_REFERENCE = "no_reference";

    private final String name;
    private final String baseUrl;
    private final int commandTimeoutMs;
    private final AtomicInteger restartCount = new AtomicInteger();
    private volatile Consumer<Boolean> healthListener;

    public CppAnalisSurfaceHttpSupervisor(String name, String baseUrl, int commandTimeoutMs) {
        this.name = Objects.requireNonNull(name);
        String u = Objects.requireNonNull(baseUrl, "baseUrl").trim();
        this.baseUrl = u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
        this.commandTimeoutMs = Math.max(100, commandTimeoutMs);
    }

    @Override
    public String supervisorLabel() {
        return name;
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public int restartCount() {
        return restartCount.get();
    }

    public void setHealthListener(Consumer<Boolean> healthListener) {
        this.healthListener = healthListener;
    }

    private void reportHealth(boolean ok) {
        Consumer<Boolean> listener = healthListener;
        if (listener != null) {
            try {
                listener.accept(ok);
            } catch (Exception ignored) {
                // listener errors must not break the pipeline
            }
        }
    }

    @Override
    public void start() throws IOException {
        health();
    }

    @Override
    public void restart() throws IOException {
        restartCount.incrementAndGet();
        start();
    }

    @Override
    public void close() {
        // внешний процесс, локального состояния нет
    }

    @Override
    public BinaryProtocol.Message health() throws IOException {
        IOException last = null;
        for (String path : List.of("/detector/health", "/health")) {
            try {
                HttpResponse<byte[]> resp = get(path);
                if (resp.statusCode() / 100 == 2) {
                    Map<String, Object> h = readJson(resp.body());
                    reportHealth(true);
                    return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, h, new byte[0]);
                }
            } catch (IOException e) {
                last = e;
            }
        }
        reportHealth(false);
        throw last == null ? new IOException("health: no path succeeded") : last;
    }

    @Override
    public BinaryProtocol.Message command(Map<String, Object> header) throws IOException {
        try {
            BinaryProtocol.Message response = commandNoRetry(header);
            reportHealth(true);
            return response;
        } catch (IOException first) {
            LOG.warn("{} command failed; retry once: {}", name, first.getMessage());
            try {
                restart();
                BinaryProtocol.Message response = commandNoRetry(header);
                reportHealth(true);
                return response;
            } catch (IOException second) {
                reportHealth(false);
                throw second;
            }
        }
    }

    @Override
    public BinaryProtocol.Message commandNoRetry(Map<String, Object> header) throws IOException {
        String op = String.valueOf(header.getOrDefault("op", ""));
        return switch (op) {
            case "stop" -> new BinaryProtocol.Message(
                    BinaryProtocol.MSG_RESPONSE,
                    Map.of("status", "ok", "service", "cpp-analis-surface-http"),
                    new byte[0]
            );
            case "health" -> health();
            case "set_reference_shm" -> uploadRefShm(header);
            case "inspect_shm" -> inspectShm(header);
            case "inspect_test_frame" -> inspectTestFrame(header);
            case "sync_client_reference_bundle" -> syncClientReferenceBundle(header);
            case "set_active_reference_view" -> ok(Map.of(
                    "product_type", String.valueOf(header.getOrDefault("product_type", "")),
                    "view_index", YamlScalars.toInt(header.get("view_index"), 0),
                    "transport", "http"));
            // FP-зоны и обучение в этом детекторе не поддерживаются: no-op, чтобы пайплайн не падал.
            case "replace_fp_zones" -> ok(Map.of(
                    "product_type", String.valueOf(header.getOrDefault("product_type", "")),
                    "zones_count", 0,
                    "supported", false));
            case "clear_inspection_context" -> clearInspectionContext();
            default -> error("unknown op=" + op + " (cpp http transport)", Map.of("op", op));
        };
    }

    // ------------------------------------------------------------------ operations

    private BinaryProtocol.Message clearInspectionContext() throws IOException {
        REFERENCE_SIGNATURES.clear();
        HttpResponse<byte[]> resp = post("/clear-inspection-context", Map.of());
        if (resp.statusCode() / 100 != 2) {
            return httpError(resp, "clear-inspection-context");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("op", "clear_inspection_context");
        out.put("transport", "http");
        Map<String, Object> body = readJson(resp.body());
        if (body.get("cleared") != null) {
            out.put("cleared", body.get("cleared"));
        }
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, out, new byte[0]);
    }

    private BinaryProtocol.Message uploadRefShm(Map<String, Object> header) throws IOException {
        Map<String, Object> body = shmFrameBody(header);
        String invalid = validateShmFrame(body, "upload-ref-shm");
        if (invalid != null) {
            return error(invalid, Map.of("op", "set_reference_shm"));
        }
        HttpResponse<byte[]> resp = post("/upload-ref-shm", body);
        if (resp.statusCode() / 100 != 2) {
            return httpError(resp, "upload-ref-shm");
        }
        Map<String, Object> h = readJson(resp.body());
        h.put("status", "ok");
        rememberReference(header);
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, h, new byte[0]);
    }

    /** Эталон + ROI активного ракурса. FP-зоны игнорируются. */
    private BinaryProtocol.Message syncClientReferenceBundle(Map<String, Object> header) throws IOException {
        String productType = String.valueOf(header.get("product_type"));
        int activeIdx = Math.max(0, Math.min(3, YamlScalars.toInt(header.get("active_reference_view_index"), 0)));
        Map<String, Object> view = findView(header.get("views"), activeIdx);
        if (view == null) {
            return error("sync_client_reference_bundle: missing view index=" + activeIdx,
                    Map.of("op", "sync_client_reference_bundle"));
        }
        int cameraId = YamlScalars.toInt(view.get("camera_id"), YamlScalars.toInt(header.get("camera_id"), -1));
        Map<String, Object> refHeader = new LinkedHashMap<>(view);
        refHeader.put("product_type", scoped(productType, cameraId));
        refHeader.put("camera_id", cameraId);
        BinaryProtocol.Message refResp = uploadRefShm(refHeader);
        if (refResp.type() == BinaryProtocol.MSG_ERROR) {
            return refResp;
        }
        List<Map<String, Object>> roi = findRoiPolygon(header.get("interest_polygons_norm"), activeIdx);
        if (roi.size() >= 3) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("product_type", scoped(productType, cameraId));
            body.put("points", roi);
            HttpResponse<byte[]> resp = post("/roi-polygon", body);
            if (resp.statusCode() / 100 != 2) {
                return httpError(resp, "roi-polygon");
            }
        }
        return ok(Map.of("product_type", productType, "active_reference_view_index", activeIdx, "transport", "http"));
    }

    private BinaryProtocol.Message inspectShm(Map<String, Object> header) throws IOException {
        BinaryProtocol.Message referenceResponse = ensureReference(header, false);
        if (referenceResponse != null) {
            return referenceResponse;
        }
        Map<String, Object> body = shmFrameBody(header);
        String invalid = validateShmFrame(body, "inspect-shm");
        if (invalid != null) {
            return error(invalid, Map.of("op", "inspect_shm"));
        }
        HttpResponse<byte[]> resp = post("/inspect-shm", body);
        if (isNoReference(resp) && header.get("reference_shm_name") != null) {
            // сервис потерял эталон (рестарт/очистка): загружаем заново и повторяем один раз
            LOG.warn("{} inspect-shm: reference lost on server, re-uploading product={}", name, body.get("product_type"));
            BinaryProtocol.Message again = ensureReference(header, true);
            if (again != null) {
                return again;
            }
            resp = post("/inspect-shm", body);
        }
        if (resp.statusCode() / 100 != 2) {
            return httpError(resp, "inspect-shm");
        }
        return decodeInspectionResponse(header, resp);
    }

    /** UI «Проверить»: JPEG с диска + временные настройки (не сохраняются на сервисе). */
    private BinaryProtocol.Message inspectTestFrame(Map<String, Object> header) throws IOException {
        BinaryProtocol.Message referenceResponse = ensureReference(header, false);
        if (referenceResponse != null) {
            return referenceResponse;
        }
        int cameraId = YamlScalars.toInt(header.get("camera_id"), -1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cache_key", str(header.get("cache_key")));
        body.put("file_path", str(header.get("file_path")));
        body.put("product_type", scoped(str(header.get("product_type")), cameraId));
        copy(body, header, "image_url");
        putProfile(body, header, cameraId);
        copy(body, header, "detector_id");
        copy(body, header, "roi_polygon_norm");
        copy(body, header, "seam_line_norm");
        PerformanceTrace.copyContext(header, body);
        copy(body, header, "heatmap_u8_output_path");
        copy(body, header, "heatmap_max_width");
        Map<String, Object> settings = ephemeralSettings(header);
        if (!settings.isEmpty()) {
            body.put("settings", settings);
        }
        if (str(body.get("cache_key")).isEmpty() || str(body.get("file_path")).isEmpty()) {
            return error("inspect-test-frame: cache_key and file_path required", Map.of("op", "inspect_test_frame"));
        }
        Path jpeg = Path.of(str(body.get("file_path")));
        if (!Files.isRegularFile(jpeg)) {
            return error("inspect-test-frame: file_path not found: " + jpeg, Map.of("op", "inspect_test_frame"));
        }
        HttpResponse<byte[]> resp = post("/inspect-test-frame", body);
        if (resp.statusCode() / 100 != 2) {
            return httpError(resp, "inspect-test-frame");
        }
        return decodeInspectionResponse(header, resp);
    }

    // ------------------------------------------------------------------ reference handling

    /** Загрузить эталон, если его подпись изменилась (или force). Возвращает ошибку или null. */
    private BinaryProtocol.Message ensureReference(Map<String, Object> header, boolean force) throws IOException {
        Object referenceShmName = header.get("reference_shm_name");
        if (referenceShmName == null || String.valueOf(referenceShmName).isBlank()) {
            return null;
        }
        String productType = str(header.get("product_type"));
        int cameraId = YamlScalars.toInt(header.get("camera_id"), -1);
        String key = scoped(productType, cameraId);
        String signature = signature(
                String.valueOf(referenceShmName),
                header.get("reference_shm_offset"),
                header.get("reference_width"),
                header.get("reference_height"),
                header.get("reference_stride"),
                fingerprint(header, String.valueOf(referenceShmName), cameraId));
        if (!force && signature.equals(REFERENCE_SIGNATURES.get(key))) {
            return null;
        }
        synchronized (SCOPE_LOCKS.computeIfAbsent(key, ignored -> new Object())) {
            if (!force && signature.equals(REFERENCE_SIGNATURES.get(key))) {
                return null;
            }
            Map<String, Object> refHeader = new LinkedHashMap<>();
            refHeader.put("product_type", key);
            refHeader.put("detector_id", header.get("detector_id"));
            refHeader.put("camera_id", cameraId);
            refHeader.put("shm_name", referenceShmName);
            refHeader.put("shm_offset", header.get("reference_shm_offset"));
            refHeader.put("width", header.get("reference_width"));
            refHeader.put("height", header.get("reference_height"));
            refHeader.put("stride", header.get("reference_stride"));
            BinaryProtocol.Message response = uploadRefShm(refHeader);
            if (response.type() == BinaryProtocol.MSG_ERROR) {
                REFERENCE_SIGNATURES.remove(key);
                return response;
            }
            REFERENCE_SIGNATURES.put(key, signature);
            return null;
        }
    }

    private void rememberReference(Map<String, Object> header) {
        String productType = str(header.get("product_type"));
        String shmName = str(header.get("shm_name"));
        if (productType.isBlank() || shmName.isBlank()) {
            return;
        }
        int cameraId = YamlScalars.toInt(header.get("camera_id"), -1);
        REFERENCE_SIGNATURES.put(scoped(productType, cameraId), signature(
                shmName, header.get("shm_offset"), header.get("width"), header.get("height"), header.get("stride"),
                fingerprint(header, shmName, cameraId)));
    }

    private static String signature(String shmName, Object offset, Object width, Object height, Object stride, String fp) {
        return String.join("|", shmName.trim(), String.valueOf(offset), String.valueOf(width),
                String.valueOf(height), String.valueOf(stride), fp == null ? "" : fp);
    }

    /** Перезапись эталона в тот же SHM меняет mtime/размер: без этого новый эталон не загрузился бы заново. */
    private static String fingerprint(Map<String, Object> header, String shmName, int cameraId) {
        Object explicit = header == null ? null : header.get("reference_content_fingerprint");
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            return String.valueOf(explicit).trim();
        }
        try {
            Path path = FrameJpegWriter.resolveShmPath(shmName, cameraId);
            if (path == null || !Files.isRegularFile(path)) {
                return "missing";
            }
            return Files.getLastModifiedTime(path).toMillis() + ":" + Files.size(path);
        } catch (Exception e) {
            return "err";
        }
    }

    // ------------------------------------------------------------------ request / response mapping

    /** Тело запроса /upload-ref-shm и /inspect-shm: только то, что понимает cppAnalisSurface. */
    private Map<String, Object> shmFrameBody(Map<String, Object> header) {
        Map<String, Object> body = new LinkedHashMap<>();
        int cameraId = YamlScalars.toInt(header.get("camera_id"), -1);
        body.put("product_type", scoped(str(header.getOrDefault("product_type", "")), cameraId));
        putProfile(body, header, cameraId);
        Object shmName = header.get("shm_name");
        if (shmName != null) {
            body.put("shm_name", logicalShmName(String.valueOf(shmName), cameraId));
        }
        copy(body, header, "width");
        copy(body, header, "height");
        copy(body, header, "stride");
        copy(body, header, "shm_offset");
        copy(body, header, "detector_id");
        copy(body, header, "roi_polygon_norm");
        copy(body, header, "seam_line_norm");
        PerformanceTrace.copyContext(header, body);
        copy(body, header, "heatmap_u8_output_path");
        copy(body, header, "heatmap_max_width");
        Map<String, Object> settings = ephemeralSettings(header);
        if (!settings.isEmpty()) {
            body.put("settings", settings);
        }
        return body;
    }

    /**
     * Временные настройки одного запроса. Новый формат: {@code analysis_test_settings.settings} или
     * {@code settings}; старый UI-формат {@code simple{threshold,sensitivity}} тоже понимается.
     */
    private static Map<String, Object> ephemeralSettings(Map<String, Object> header) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object direct = header.get("settings");
        if (direct instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(String.valueOf(k), v);
                }
            });
        }
        if (out.isEmpty() && header.get("analysis_test_settings") instanceof Map<?, ?> test) {
            Object nested = test.get("settings");
            if (nested instanceof Map<?, ?> m) {
                m.forEach((k, v) -> {
                    if (k != null && v != null) {
                        out.put(String.valueOf(k), v);
                    }
                });
            }
        }
        if (out.isEmpty() && header.get("simple") instanceof Map<?, ?> simple) {
            Object threshold = simple.get("threshold");
            Object sensitivity = simple.get("sensitivity");
            if (threshold != null) {
                out.put("threshold", threshold);
            }
            if (sensitivity != null) {
                out.put("sensitivity", sensitivity);
            }
        }
        return out;
    }

    private static void putProfile(Map<String, Object> body, Map<String, Object> header, int cameraId) {
        Object explicit = header.get("analysis_profile");
        String profile = explicit == null ? "" : String.valueOf(explicit).trim();
        if (profile.isEmpty()) {
            String resolved = CameraAnalysisProfiles.resolve(cameraId, null);
            profile = resolved == null ? "" : resolved.trim();
        }
        if (!profile.isEmpty()) {
            body.put("analysis_profile", profile);
        }
    }

    private BinaryProtocol.Message decodeInspectionResponse(Map<String, Object> header, HttpResponse<byte[]> response) throws IOException {
        long started = System.nanoTime();
        try { return inspectResponse(header, readJson(response.body())); }
        finally {
            Map<String, Object> fields = PerformanceTrace.context(header);
            fields.put("parent_request_id", fields.get("request_id"));
            fields.put("request_id", response.headers().firstValue("X-Request-Id").orElse("unknown"));
            fields.put("duration_ms", PerformanceTrace.elapsedMs(started));
            PerformanceTrace.emit("cpp_http_client", "response_decode", fields);
        }
    }

    private BinaryProtocol.Message inspectResponse(Map<String, Object> header, Map<String, Object> json) {
        String status = String.valueOf(json.getOrDefault("status", "UNKNOWN"));
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        boolean ok = !("БРАК".equals(normalized) || "FAIL".equals(normalized)
                || "ERROR".equals(normalized) || "REJECT".equals(normalized));
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", status);
        h.put("ok", ok);
        h.put("anomaly_score", YamlScalars.toDouble(json.get("anomaly_score"), 0.0));
        h.put("threshold", YamlScalars.toDouble(json.get("threshold"), 0.0));
        h.put("product_type", str(header.getOrDefault("product_type", json.get("product_type"))));
        h.put("detector_id", String.valueOf(json.getOrDefault("detector_id", "")));
        h.put("raw_anomaly_score", YamlScalars.toDouble(json.get("raw_anomaly_score"), 0.0));
        // поля, оставшиеся от Python-контракта: всегда пустые, пайплайн их читает
        h.put("rechecked_zones_count", 0);
        h.put("recheck_adjustment", 0.0);
        h.put("rechecked_zone_ids", List.of());
        h.put("learned_normal_matches_count", 0);
        h.put("learned_normal_adjustment", 0.0);
        h.put("excluded_normal_zones", List.of());
        for (String timing : List.of("py_align_ms", "py_diff_ms", "py_anomaly_ms", "py_fp_recheck_ms", "py_heatmap_ms", "py_total_ms")) {
            h.put(timing, YamlScalars.toDouble(json.get(timing), 0.0));
        }
        // нативные поля cppAnalisSurface
        for (String key : List.of("area_percent", "reason", "shift", "zones", "settings", "frame_width", "frame_height", "seam", "lines")) {
            if (json.get(key) != null) {
                h.put(key, json.get(key));
            }
        }
        Object hm = json.get("heatmap_u8");
        if (hm instanceof Map<?, ?> heatmap) {
            h.put("heatmap_u8_path", heatmap.get("path"));
            h.put("heatmap_u8_width", heatmap.get("width"));
            h.put("heatmap_u8_height", heatmap.get("height"));
            h.put("heatmap_u8_stride", heatmap.get("stride"));
            h.put("heatmap_width", heatmap.get("width"));
            h.put("heatmap_height", heatmap.get("height"));
        }
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, h, new byte[0]);
    }

    /** Короткое имя файла в iml_shm (без каталога и .bin), как ожидает сервис. */
    static String logicalShmName(String shmName, int cameraId) {
        Path resolved = FrameJpegWriter.resolveShmPath(shmName, cameraId);
        if (resolved != null) {
            String base = resolved.getFileName().toString();
            return base.endsWith(".bin") ? base.substring(0, base.length() - 4) : base;
        }
        String s = shmName.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        if (s.endsWith(".bin")) {
            s = s.substring(0, s.length() - 4);
        }
        if (s.isEmpty() && cameraId >= 0) {
            return "iml_cam_" + cameraId + "_frame";
        }
        return s;
    }

    private static String scoped(String productType, int cameraId) {
        String normalized = productType == null ? "" : productType.trim();
        if (normalized.isEmpty() || cameraId < 0) {
            return normalized;
        }
        String suffix = "#cam=" + cameraId;
        return normalized.endsWith(suffix) ? normalized : normalized + suffix;
    }

    private String validateShmFrame(Map<String, Object> body, String op) {
        List<String> missing = new ArrayList<>();
        if (str(body.get("product_type")).isEmpty()) {
            missing.add("product_type");
        }
        if (str(body.get("shm_name")).isEmpty()) {
            missing.add("shm_name");
        }
        if (YamlScalars.toInt(body.get("width"), 0) <= 0) {
            missing.add("width");
        }
        if (YamlScalars.toInt(body.get("height"), 0) <= 0) {
            missing.add("height");
        }
        if (missing.isEmpty()) {
            return null;
        }
        String msg = op + " skipped: missing/invalid required fields " + missing;
        LOG.warn("{} {}", name, msg);
        return msg;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findView(Object viewsObj, int index) {
        if (!(viewsObj instanceof List<?> views)) {
            return null;
        }
        for (Object o : views) {
            if (o instanceof Map<?, ?> m && YamlScalars.toInt(m.get("view_index"), -1) == index) {
                return (Map<String, Object>) m;
            }
        }
        if (index >= 0 && index < views.size() && views.get(index) instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    private static List<Map<String, Object>> findRoiPolygon(Object polysObj, int index) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(polysObj instanceof List<?> polys)) {
            return out;
        }
        for (Object o : polys) {
            if (o instanceof Map<?, ?> m && YamlScalars.toInt(m.get("view_index"), -1) == index
                    && m.get("points") instanceof List<?> pts) {
                for (Object p : pts) {
                    if (p instanceof Map<?, ?> pm) {
                        double x = YamlScalars.toDouble(pm.get("x"), Double.NaN);
                        double y = YamlScalars.toDouble(pm.get("y"), Double.NaN);
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
        }
        return out;
    }

    // ------------------------------------------------------------------ small helpers

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void copy(Map<String, Object> to, Map<String, Object> from, String key) {
        Object v = from.get(key);
        if (v != null) {
            to.put(key, v);
        }
    }

    private static BinaryProtocol.Message ok(Map<String, Object> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.putAll(fields);
        return new BinaryProtocol.Message(BinaryProtocol.MSG_RESPONSE, out, new byte[0]);
    }

    private static BinaryProtocol.Message error(String message, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>(extra);
        out.put("error", message);
        return new BinaryProtocol.Message(BinaryProtocol.MSG_ERROR, out, new byte[0]);
    }

    private static BinaryProtocol.Message httpError(HttpResponse<byte[]> resp, String ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", errorText(ctx, resp));
        out.put("http_status", resp.statusCode());
        String code = errorCode(resp);
        if (code != null) {
            out.put("error_code", code);
        }
        return new BinaryProtocol.Message(BinaryProtocol.MSG_ERROR, out, new byte[0]);
    }

    private static boolean isNoReference(HttpResponse<byte[]> resp) {
        return resp.statusCode() == 400 && NO_REFERENCE.equals(errorCode(resp));
    }

    private static String errorCode(HttpResponse<byte[]> resp) {
        try {
            Object code = readJson(resp.body()).get("error_code");
            return code == null ? null : String.valueOf(code);
        } catch (Exception e) {
            return null;
        }
    }

    private static String errorText(String ctx, HttpResponse<byte[]> resp) {
        String msg = ctx + " HTTP " + resp.statusCode();
        byte[] body = resp.body();
        if (body != null && body.length > 0) {
            try {
                Object detail = MAPPER.readValue(body, MAP_TYPE).get("detail");
                if (detail != null) {
                    return msg + ": " + detail;
                }
            } catch (Exception ignored) {
                // not JSON
            }
            String text = new String(body, StandardCharsets.UTF_8);
            msg = msg + ": " + (text.length() > 500 ? text.substring(0, 500) + "..." : text);
        }
        return msg;
    }

    private static Map<String, Object> readJson(byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(MAPPER.readValue(body, MAP_TYPE));
    }

    private HttpResponse<byte[]> get(String path) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(commandTimeoutMs))
                .GET()
                .header("Accept", "application/json")
                .build();
        return send(req);
    }

    private HttpResponse<byte[]> post(String path, Map<String, Object> jsonBody) throws IOException {
        long started = System.nanoTime();
        jsonBody = new LinkedHashMap<>(jsonBody);
        Map<String, Object> fields = PerformanceTrace.context(jsonBody);
        fields.put("parent_request_id", jsonBody.get("request_id"));
        String requestId = PerformanceTrace.requestId();
        fields.put("request_id", requestId);
        jsonBody.put("request_id", requestId);
        long serializeStarted = System.nanoTime();
        byte[] json = MAPPER.writeValueAsBytes(jsonBody);
        fields.put("request_serialize_ms", PerformanceTrace.elapsedMs(serializeStarted));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(commandTimeoutMs))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("X-Request-Id", requestId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(json));
        for (String key : List.of("cycle_id", "trigger_sequence", "camera_id", "frame_id")) {
            Object value = fields.get(key);
            if (value != null) builder.header("X-" + key.replace('_', '-'), String.valueOf(value));
        }
        HttpRequest req = builder.build();
        long rpcStarted = System.nanoTime();
        fields.put("path", path);
        fields.put("server", baseUrl);
        PerformanceTrace.emit("cpp_http_client", "http_request_started", fields);
        HttpResponse<byte[]> resp;
        try {
            resp = send(req);
            fields.put("status_code", resp.statusCode());
            fields.put("response_bytes", resp.body() == null ? 0 : resp.body().length);
        } catch (IOException error) {
            fields.put("error", error.getClass().getSimpleName());
            throw error;
        } finally {
            fields.put("path", path);
            fields.put("server", baseUrl);
            fields.put("rpc_ms", PerformanceTrace.elapsedMs(rpcStarted));
            fields.put("total_ms", PerformanceTrace.elapsedMs(started));
            PerformanceTrace.emit("cpp_http_client", "http_request", fields);
        }
        if (resp.statusCode() / 100 != 2) {
            LOG.warn("{} HTTP POST {} failed status={} response={}", name, path, resp.statusCode(),
                    new String(resp.body() == null ? new byte[0] : resp.body(), StandardCharsets.UTF_8));
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
}
