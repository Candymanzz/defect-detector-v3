package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.referencedraft.ReferenceDraftCoordinator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP оркестратора для клиента: health, батч метаданных камер, черновик эталона, результат инспекции, FP через analisSurface.
 */
public final class UiHttpServer implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(UiHttpServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record ClientPreviewArtifact(Path path, int width, int height) {
    }

    /**
     * Последний снимок по камере (для {@code /api/cameras/latest.json}); файлы остаются на диске, в JSON отдаются только абсолютные пути при наличии файла.
     */
    public record Latest(
            long frameId,
            String productType,
            String detectorId,
            String shmName,
            int captureWidth,
            int captureHeight,
            Path currentJpeg,
            int currentJpegWidth,
            int currentJpegHeight,
            Path heatmapU8,
            int heatmapU8Width,
            int heatmapU8Height,
            long updatedAtEpochMs
    ) {
    }

    private final HttpServer httpServer;
    private final Map<Integer, Latest> latestByCamera = new ConcurrentHashMap<>();
    private final ReferenceDraftCoordinator referenceDraftCoordinator;
    private final InspectionResultCache inspectionResultCache;
    private final ClientWebSocketServer orchestratorClientWs;
    private final String analisSurfaceHttpBaseUrl;
    private static final HttpClient AS_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public UiHttpServer(
            String host,
            int port,
            ReferenceDraftCoordinator referenceDraftCoordinator,
            InspectionResultCache inspectionResultCache,
            ClientWebSocketServer orchestratorClientWs,
            String analisSurfaceHttpBaseUrl
    ) throws IOException {
        this.referenceDraftCoordinator = referenceDraftCoordinator;
        this.inspectionResultCache = inspectionResultCache;
        this.orchestratorClientWs = orchestratorClientWs;
        this.analisSurfaceHttpBaseUrl = analisSurfaceHttpBaseUrl == null ? "" : analisSurfaceHttpBaseUrl.trim();
        InetSocketAddress addr = new InetSocketAddress(host, port);
        this.httpServer = HttpServer.create(addr, 0);
        httpServer.createContext("/health", UiHttpServer::handleHealth);
        httpServer.createContext("/api/cameras/latest.json", this::handleCamerasLatestBatch);
        httpServer.createContext("/api/reference-draft/", this::handleReferenceDraft);
        if (this.inspectionResultCache != null) {
            httpServer.createContext("/api/inspection/result", this::handleInspectionResult);
        }
        if (this.orchestratorClientWs != null && !this.analisSurfaceHttpBaseUrl.isEmpty()) {
            httpServer.createContext("/api/orchestrator/", this::handleOrchestratorPrefix);
        }
        httpServer.setExecutor(null);
        httpServer.start();
    }

    private void handleInspectionResult(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (inspectionResultCache == null) {
            sendClientJsonError(ex, 503, "inspection result cache not configured");
            return;
        }
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        int cam = parseIntSafe(q.get("camera_id"), -1);
        if (cam < 0) {
            sendClientJsonError(ex, 400, "camera_id query parameter required");
            return;
        }
        String frameRaw = q.get("frame_id");
        Optional<byte[]> env;
        if (frameRaw != null && !frameRaw.isBlank()) {
            long fid = parseLongSafe(frameRaw.trim(), -1L);
            if (fid < 0) {
                sendClientJsonError(ex, 400, "invalid frame_id");
                return;
            }
            env = inspectionResultCache.getEnvelopeUtf8(cam, fid);
        } else {
            env = inspectionResultCache.getLatestEnvelopeUtf8(cam);
        }
        if (env.isEmpty()) {
            sendClientJsonError(ex, 404, "no inspection result for this camera/frame");
            return;
        }
        corsJson(ex);
        send(ex, 200, "application/json; charset=utf-8", env.get());
    }

    private void handleOrchestratorPrefix(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Accept");
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (orchestratorClientWs == null || analisSurfaceHttpBaseUrl.isEmpty()) {
            sendClientJsonError(ex, 503, "orchestrator fp api not configured");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path == null) {
            send(ex, 404, "text/plain", "not found\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if ("/api/orchestrator/fp-zones".equals(path)) {
            handleOrchestratorFpZonesRoot(ex);
            return;
        }
        if (path.startsWith("/api/orchestrator/fp-zones/")) {
            String zoneId = path.substring("/api/orchestrator/fp-zones/".length());
            if (zoneId.isEmpty()) {
                send(ex, 404, "text/plain", "not found\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            handleOrchestratorFpZoneDelete(ex, zoneId);
            return;
        }
        send(ex, 404, "text/plain", "not found\n".getBytes(StandardCharsets.UTF_8));
    }

    private void handleOrchestratorFpZonesRoot(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            String pt = q.get("product_type");
            if (pt == null || pt.isBlank()) {
                sendClientJsonError(ex, 400, "product_type query parameter required");
                return;
            }
            try {
                HttpResult r = httpToAnalisSurface(
                        "GET",
                        "/fp-zones/" + java.net.URLEncoder.encode(pt.trim(), StandardCharsets.UTF_8),
                        null,
                        null
                );
                corsJson(ex);
                ex.getResponseHeaders().set("Content-Type", r.contentType());
                ex.sendResponseHeaders(r.status(), r.body().length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(r.body());
                }
            } catch (Exception e) {
                sendClientJsonError(ex, 502, e.getMessage() == null ? "proxy failed" : e.getMessage());
            }
            return;
        }
        if ("POST".equalsIgnoreCase(method)) {
            byte[] reqBody;
            try (InputStream in = ex.getRequestBody()) {
                reqBody = in.readAllBytes();
            }
            try {
                HttpResult r = httpToAnalisSurface("POST", "/fp-zones", reqBody, ex.getRequestHeaders().getFirst("Content-Type"));
                if (r.status() / 100 == 2) {
                    syncFpAfterMutation(reqBody, r.body());
                }
                corsJson(ex);
                ex.getResponseHeaders().set("Content-Type", r.contentType());
                ex.sendResponseHeaders(r.status(), r.body().length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(r.body());
                }
            } catch (Exception e) {
                sendClientJsonError(ex, 502, e.getMessage() == null ? "proxy failed" : e.getMessage());
            }
            return;
        }
        send(ex, 405, "text/plain", "method not allowed\n".getBytes(StandardCharsets.UTF_8));
    }

    private void handleOrchestratorFpZoneDelete(HttpExchange ex, String zoneId) throws IOException {
        if (!"DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String pt = q.get("product_type");
        if (pt == null || pt.isBlank()) {
            sendClientJsonError(ex, 400, "product_type query parameter required for fp zone delete sync");
            return;
        }
        String encZone = java.net.URLEncoder.encode(zoneId, StandardCharsets.UTF_8);
        try {
            HttpResult r = httpToAnalisSurface("DELETE", "/fp-zones/" + encZone, null, null);
            if (r.status() / 100 == 2) {
                try {
                    orchestratorClientWs.pullFpZonesFromAnalisSurfaceHttp(analisSurfaceHttpBaseUrl, pt.trim());
                } catch (Exception syncEx) {
                    LOG.warn("orchestrator fp-zones sync after DELETE failed: {}", syncEx.getMessage());
                }
            }
            corsJson(ex);
            ex.getResponseHeaders().set("Content-Type", r.contentType());
            ex.sendResponseHeaders(r.status(), r.body().length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(r.body());
            }
        } catch (Exception e) {
            sendClientJsonError(ex, 502, e.getMessage() == null ? "proxy failed" : e.getMessage());
        }
    }

    private void syncFpAfterMutation(byte[] requestBody, byte[] responseBody) {
        String pt = extractProductType(requestBody);
        if (pt.isBlank()) {
            pt = extractProductType(responseBody);
        }
        if (pt.isBlank()) {
            pt = orchestratorClientWs.referenceContext()
                    .snapshot()
                    .map(ReferenceBundleSnapshot::productType)
                    .orElse("");
        }
        if (pt.isBlank()) {
            return;
        }
        try {
            orchestratorClientWs.pullFpZonesFromAnalisSurfaceHttp(analisSurfaceHttpBaseUrl, pt);
        } catch (Exception e) {
            LOG.warn("orchestrator fp-zones sync after mutation failed: {}", e.getMessage());
        }
    }

    private static String extractProductType(byte[] jsonUtf8) {
        if (jsonUtf8 == null || jsonUtf8.length == 0) {
            return "";
        }
        try {
            JsonNode n = JSON.readTree(jsonUtf8);
            String t = n.path("product_type").asText("").trim();
            return t == null ? "" : t;
        } catch (Exception e) {
            return "";
        }
    }

    private record HttpResult(int status, byte[] body, String contentType) {
        private HttpResult {
            body = body == null ? new byte[0] : body;
            contentType = contentType == null || contentType.isBlank() ? "application/json; charset=utf-8" : contentType;
        }
    }

    private HttpResult httpToAnalisSurface(String method, String pathSuffix, byte[] body, String requestContentType)
            throws IOException, InterruptedException {
        String root = analisSurfaceHttpBaseUrl.endsWith("/")
                ? analisSurfaceHttpBaseUrl.substring(0, analisSurfaceHttpBaseUrl.length() - 1)
                : analisSurfaceHttpBaseUrl;
        String url = root + pathSuffix;
        String m = method.toUpperCase();
        boolean noBody = "GET".equals(m) || "HEAD".equals(m) || body == null || body.length == 0;
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .method(m, noBody ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (!noBody) {
            String ct = requestContentType != null && !requestContentType.isBlank()
                    ? requestContentType
                    : "application/json; charset=utf-8";
            rb.header("Content-Type", ct);
        }
        rb.header("Accept", "application/json");
        HttpResponse<byte[]> resp = AS_HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
        String ct = resp.headers().firstValue("Content-Type").orElse("application/json; charset=utf-8");
        return new HttpResult(resp.statusCode(), resp.body() == null ? new byte[0] : resp.body(), ct);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i <= 0) {
                continue;
            }
            String k = URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static int parseIntSafe(String s, int d) {
        if (s == null || s.isBlank()) {
            return d;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static long parseLongSafe(String s, long d) {
        if (s == null || s.isBlank()) {
            return d;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return d;
        }
    }

    /**
     * Пять слотов камер {@code 0…4}: метаданные и при наличии файлов — абсолютные пути на диске (без URL превью-эндпоинтов).
     */
    private void handleCamerasLatestBatch(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed\n".getBytes());
            return;
        }
        corsJson(ex);
        ArrayNode arr = JSON.createArrayNode();
        for (int cam = 0; cam < 5; cam++) {
            Latest l = latestByCamera.get(cam);
            arr.add(l == null ? emptyCameraLatestJson(cam) : latestJson(cam, l));
        }
        ObjectNode root = JSON.createObjectNode();
        root.set("cameras", arr);
        root.put("server_ts_ms", System.currentTimeMillis());
        if (referenceDraftCoordinator != null) {
            root.put("reference_draft_paused", referenceDraftCoordinator.isPaused());
        }
        byte[] body = JSON.writeValueAsBytes(root);
        send(ex, 200, "application/json; charset=utf-8", body);
    }

    private ObjectNode emptyCameraLatestJson(int cameraId) {
        ObjectNode root = JSON.createObjectNode();
        root.put("cameraId", cameraId);
        root.put("frameId", -1L);
        root.put("productType", "");
        root.put("detectorId", "");
        root.put("shmName", "");
        root.put("updatedAtMs", 0L);
        root.put("hasCurrent", false);
        root.put("hasHeatmap", false);
        ObjectNode cap = root.putObject("capture");
        cap.put("width", 0);
        cap.put("height", 0);
        ObjectNode cur = root.putObject("currentJpeg");
        cur.put("width", 0);
        cur.put("height", 0);
        ObjectNode hm = root.putObject("heatmapU8");
        hm.put("width", 0);
        hm.put("height", 0);
        if (referenceDraftCoordinator != null && referenceDraftCoordinator.isPaused()) {
            root.put("reference_draft_paused", true);
        }
        return root;
    }

    private ObjectNode latestJson(int cameraId, Latest l) {
        boolean hasCur = l.currentJpeg() != null && l.currentJpegWidth() > 0 && Files.isRegularFile(l.currentJpeg());
        boolean hasHm = l.heatmapU8() != null && l.heatmapU8Width() > 0 && l.heatmapU8Height() > 0 && Files.isRegularFile(l.heatmapU8());
        ObjectNode root = JSON.createObjectNode();
        root.put("cameraId", cameraId);
        root.put("frameId", l.frameId());
        root.put("productType", l.productType() == null ? "" : l.productType());
        root.put("detectorId", l.detectorId() == null ? "" : l.detectorId());
        root.put("shmName", l.shmName() == null ? "" : l.shmName());
        root.put("updatedAtMs", l.updatedAtEpochMs());
        root.put("hasCurrent", hasCur);
        root.put("hasHeatmap", hasHm);
        ObjectNode cap = root.putObject("capture");
        cap.put("width", l.captureWidth());
        cap.put("height", l.captureHeight());
        ObjectNode cur = root.putObject("currentJpeg");
        cur.put("width", l.currentJpegWidth());
        cur.put("height", l.currentJpegHeight());
        if (hasCur) {
            cur.put("file_path", l.currentJpeg().toAbsolutePath().toString());
        }
        ObjectNode hm = root.putObject("heatmapU8");
        hm.put("width", l.heatmapU8Width());
        hm.put("height", l.heatmapU8Height());
        if (hasHm) {
            hm.put("file_path", l.heatmapU8().toAbsolutePath().toString());
        }
        if (referenceDraftCoordinator != null && referenceDraftCoordinator.isPaused()) {
            root.put("reference_draft_paused", true);
        }
        return root;
    }

    private void handleReferenceDraft(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Accept");
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (referenceDraftCoordinator == null) {
            sendClientJsonError(ex, 503, "reference_draft requires client_ws and coordinator wiring");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        try {
            if (path.endsWith("/status")) {
                if (!"GET".equalsIgnoreCase(method)) {
                    send(ex, 405, "text/plain", "method not allowed\n".getBytes());
                    return;
                }
                corsJson(ex);
                byte[] body = JSON.writeValueAsBytes(referenceDraftCoordinator.statusJson());
                send(ex, 200, "application/json; charset=utf-8", body);
                return;
            }
            if (path.endsWith("/start")) {
                if (!"POST".equalsIgnoreCase(method)) {
                    send(ex, 405, "text/plain", "method not allowed\n".getBytes());
                    return;
                }
                corsJson(ex);
                referenceDraftCoordinator.startDraft();
                send(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.endsWith("/cancel")) {
                if (!"POST".equalsIgnoreCase(method)) {
                    send(ex, 405, "text/plain", "method not allowed\n".getBytes());
                    return;
                }
                corsJson(ex);
                referenceDraftCoordinator.cancelDraft();
                send(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.endsWith("/confirm")) {
                if (!"POST".equalsIgnoreCase(method)) {
                    send(ex, 405, "text/plain", "method not allowed\n".getBytes());
                    return;
                }
                corsJson(ex);
                byte[] raw;
                try (InputStream in = ex.getRequestBody()) {
                    raw = in.readAllBytes();
                }
                referenceDraftCoordinator.confirmDraft(raw);
                send(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
                return;
            }
        } catch (IllegalStateException e) {
            sendClientJsonError(ex, 409, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            sendClientJsonError(ex, 400, e.getMessage());
            return;
        } catch (Exception e) {
            sendClientJsonError(ex, 500, e.getMessage() == null ? "confirm failed" : e.getMessage());
            return;
        }
        send(ex, 404, "text/plain", "not found\n".getBytes());
    }

    private void sendClientJsonError(HttpExchange ex, int code, String msg) throws IOException {
        corsJson(ex);
        String json = "{\"error\":\"" + msg.replace("\"", "'").replace("\n", " ") + "\"}";
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        send(ex, code, "application/json; charset=utf-8", b);
    }

    private static void corsJson(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    }

    private static void handleHealth(HttpExchange ex) throws IOException {
        send(ex, 200, "text/plain", "ok\n".getBytes());
    }

    private static void send(HttpExchange ex, int code, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    public Optional<Latest> latest(int cameraId) {
        return Optional.ofNullable(latestByCamera.get(cameraId));
    }

    public void update(
            int cameraId,
            long frameId,
            String productType,
            String detectorId,
            String shmName,
            int captureWidth,
            int captureHeight,
            Path currentJpeg,
            int currentJpegW,
            int currentJpegH,
            Path heatmapU8,
            int heatmapU8W,
            int heatmapU8H
    ) {
        if (referenceDraftCoordinator != null && referenceDraftCoordinator.isPaused()) {
            return;
        }
        latestByCamera.put(
                cameraId,
                new Latest(
                        frameId,
                        productType == null ? "" : productType,
                        detectorId == null ? "" : detectorId,
                        shmName == null ? "" : shmName,
                        captureWidth,
                        captureHeight,
                        currentJpeg,
                        currentJpegW,
                        currentJpegH,
                        heatmapU8,
                        heatmapU8W,
                        heatmapU8H,
                        System.currentTimeMillis()
                )
        );
    }

    @Override
    public void close() {
        httpServer.stop(0);
    }

    private static Path resolveImlShmPath(String fileNameInShmDir) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String la = System.getenv("LOCALAPPDATA");
            if (la == null || la.isBlank()) {
                la = System.getenv("TEMP");
            }
            if (la == null || la.isBlank()) {
                la = ".";
            }
            return Path.of(la, "iml_shm", fileNameInShmDir);
        }
        return Path.of("/dev/shm", fileNameInShmDir);
    }

    public static ClientPreviewArtifact writeCurrentJpegFromBgrShm(
            String shmName, int width, int height, int stride, int previewMaxWidth, float quality
    ) {
        if (width <= 0 || height <= 0 || stride < width * 3) {
            return new ClientPreviewArtifact(null, 0, 0);
        }
        String base = shmName.startsWith("/") ? shmName.substring(1) : shmName;
        base = base.replace('/', '_');
        Path shmPath = resolveImlShmPath(base);
        if (!Files.isRegularFile(shmPath)) {
            return new ClientPreviewArtifact(null, 0, 0);
        }
        long need = (long) stride * (long) height;
        try (FileChannel ch = FileChannel.open(shmPath, StandardOpenOption.READ)) {
            long mapLen = Math.min(need, Math.max(0, ch.size()));
            if (mapLen < (long) width * 3L * height) {
                return new ClientPreviewArtifact(null, 0, 0);
            }
            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, mapLen);
            byte[] bgr = new byte[width * height * 3];
            for (int y = 0; y < height; y++) {
                buf.position(y * stride);
                buf.get(bgr, y * width * 3, width * 3);
            }
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            byte[] dst = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            System.arraycopy(bgr, 0, dst, 0, bgr.length);

            int outW = width;
            int outH = height;
            if (previewMaxWidth > 0 && width > previewMaxWidth) {
                outW = previewMaxWidth;
                outH = Math.max(1, (int) Math.round((double) height * previewMaxWidth / width));
                BufferedImage scaled = new BufferedImage(outW, outH, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, outW, outH, null);
                g.dispose();
                img = scaled;
            }

            Path out = Files.createTempFile("iml-ui-current-", ".jpg");
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) {
                return new ClientPreviewArtifact(null, 0, 0);
            }
            ImageWriter writer = writers.next();
            float q = Math.min(1f, Math.max(0.05f, quality));
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out.toFile())) {
                writer.setOutput(ios);
                ImageWriteParam p = writer.getDefaultWriteParam();
                if (p.canWriteCompressed()) {
                    p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    p.setCompressionQuality(q);
                }
                writer.write(null, new IIOImage(img, null, null), p);
            } finally {
                writer.dispose();
            }
            return new ClientPreviewArtifact(out, outW, outH);
        } catch (Exception e) {
            return new ClientPreviewArtifact(null, 0, 0);
        }
    }
}
