package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.KopcheniHttpProxy;
import com.example.iml.orchestrator.integration.openapi.OrchestratorApiDocumentationHandlers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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
import java.nio.charset.StandardCharsets;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Локальный HTTP для превью current/heatmap и (при наличии {@link GeometrySnapshotCache}) последнего ответа java-geometry по камере (секция {@code ui_http} в YAML).
 */
public final class UiHttpServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record ClientPreviewArtifact(Path path, int width, int height) {
    }

    /**
     * Последний опубликованный снимок для камеры (метаданные + пути к файлам артефактов).
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
    private final GeometrySnapshotCache geometrySnapshotCache;
    private final ClientApiMount clientApi;
    private final HeatmapArtifactRegistry heatmapArtifacts = new HeatmapArtifactRegistry();

    public UiHttpServer(String host, int port) throws IOException {
        this(host, port, null, ClientApiMount.disabled());
    }

    public UiHttpServer(String host, int port, GeometrySnapshotCache geometrySnapshotCache) throws IOException {
        this(host, port, geometrySnapshotCache, ClientApiMount.disabled());
    }

    public UiHttpServer(String host, int port, GeometrySnapshotCache geometrySnapshotCache, ClientApiMount clientApi) throws IOException {
        this.geometrySnapshotCache = geometrySnapshotCache;
        this.clientApi = clientApi == null ? ClientApiMount.disabled() : clientApi;
        InetSocketAddress addr = new InetSocketAddress(host, port);
        this.httpServer = HttpServer.create(addr, 0);
        httpServer.createContext("/health", UiHttpServer::handleHealth);
        httpServer.createContext("/api/cameras", this::handleCamerasList);
        httpServer.createContext("/api/camera/", this::handleCameraPath);
        httpServer.createContext("/api/heatmap-artifact/", this::handleHeatmapArtifact);
        if (geometrySnapshotCache != null) {
            httpServer.createContext("/api/geometry/cameras", this::handleGeometryCamerasList);
            httpServer.createContext("/api/geometry/camera/", this::handleGeometryCameraPath);
        }
        if (this.clientApi.enabled()) {
            httpServer.createContext("/api/client/", this::handleClientApi);
        }
        OrchestratorApiDocumentationHandlers.register(httpServer);
        httpServer.setExecutor(null);
        httpServer.start();
    }

    private void handleCamerasList(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed\n".getBytes());
            return;
        }
        corsJson(ex);
        ArrayNode ids = JSON.createArrayNode();
        List<Integer> keys = new ArrayList<>(latestByCamera.keySet());
        Collections.sort(keys);
        for (int cam : keys) {
            Latest l = latestByCamera.get(cam);
            if (l == null) {
                continue;
            }
            boolean hasCur = l.currentJpeg() != null && l.currentJpegWidth() > 0 && Files.isRegularFile(l.currentJpeg());
            boolean hasHm = l.heatmapU8() != null && l.heatmapU8Width() > 0 && l.heatmapU8Height() > 0 && Files.isRegularFile(l.heatmapU8());
            if (hasCur || hasHm) {
                ids.add(cam);
            }
        }
        ObjectNode root = JSON.createObjectNode();
        root.set("cameras", ids);
        byte[] body = JSON.writeValueAsBytes(root);
        send(ex, 200, "application/json; charset=utf-8", body);
    }

    private void handleGeometryCamerasList(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed\n".getBytes());
            return;
        }
        corsJson(ex);
        ArrayNode ids = JSON.createArrayNode();
        List<Integer> keys = new ArrayList<>(geometrySnapshotCache.cameraIds());
        Collections.sort(keys);
        for (int cam : keys) {
            ids.add(cam);
        }
        ObjectNode root = JSON.createObjectNode();
        root.set("cameras", ids);
        byte[] body = JSON.writeValueAsBytes(root);
        send(ex, 200, "application/json; charset=utf-8", body);
    }

    private void handleGeometryCameraPath(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed\n".getBytes());
            return;
        }
        String uri = ex.getRequestURI().getPath();
        try {
            String[] parts = uri.split("/");
            if (parts.length < 6) {
                send(ex, 404, "text/plain", "not found\n".getBytes());
                return;
            }
            int cam = Integer.parseInt(parts[4]);
            GeometrySnapshotCache.Snapshot snap = geometrySnapshotCache.get(cam).orElse(null);
            if (snap == null) {
                send(ex, 404, "text/plain", "no geometry snapshot\n".getBytes());
                return;
            }
            if (uri.endsWith("/latest.json")) {
                corsJson(ex);
                byte[] body = JSON.writeValueAsBytes(geometryLatestJson(cam, snap));
                send(ex, 200, "application/json; charset=utf-8", body);
                return;
            }
        } catch (Exception ignored) {
        }
        send(ex, 404, "text/plain", "not found\n".getBytes());
    }

    private static ObjectNode geometryLatestJson(int cameraId, GeometrySnapshotCache.Snapshot snap) {
        ObjectNode root = JSON.createObjectNode();
        root.put("cameraId", cameraId);
        root.put("frameId", snap.frameId());
        root.put("updatedAtMs", snap.updatedAtEpochMs());
        root.put("latestJsonPath", "/api/geometry/camera/" + cameraId + "/latest.json");
        root.set("geometry", JSON.valueToTree(snap.geometryHeader()));
        return root;
    }

    private void handleCameraPath(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed\n".getBytes());
            return;
        }
        String uri = ex.getRequestURI().getPath();
        try {
            String[] parts = uri.split("/");
            if (parts.length < 5) {
                send(ex, 404, "text/plain", "not found\n".getBytes());
                return;
            }
            int cam = Integer.parseInt(parts[3]);
            Latest l = latestByCamera.get(cam);
            if (l == null) {
                send(ex, 404, "text/plain", "no data\n".getBytes());
                return;
            }
            if (uri.endsWith("/latest.json")) {
                corsJson(ex);
                byte[] body = JSON.writeValueAsBytes(latestJson(cam, l));
                send(ex, 200, "application/json; charset=utf-8", body);
                return;
            }
            if (uri.endsWith("/current.jpg") && l.currentJpeg() != null && Files.isRegularFile(l.currentJpeg())) {
                byte[] data = Files.readAllBytes(l.currentJpeg());
                send(ex, 200, "image/jpeg", data);
                return;
            }
            if (uri.endsWith("/heatmap.u8") && l.heatmapU8() != null && Files.isRegularFile(l.heatmapU8())) {
                byte[] data = Files.readAllBytes(l.heatmapU8());
                send(ex, 200, "application/octet-stream", data);
                return;
            }
        } catch (Exception ignored) {
        }
        send(ex, 404, "text/plain", "not found\n".getBytes());
    }

    private void handleHeatmapArtifact(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed\n".getBytes());
            return;
        }
        String uri = ex.getRequestURI().getPath();
        String prefix = "/api/heatmap-artifact/";
        if (!uri.startsWith(prefix)) {
            send(ex, 404, "text/plain", "not found\n".getBytes());
            return;
        }
        String token = uri.substring(prefix.length());
        int slash = token.indexOf('/');
        if (slash >= 0) {
            token = token.substring(0, slash);
        }
        if (token.isEmpty()) {
            send(ex, 404, "text/plain", "not found\n".getBytes());
            return;
        }
        Path file = heatmapArtifacts.resolve(token);
        if (file == null) {
            send(ex, 404, "text/plain", "not found\n".getBytes());
            return;
        }
        try {
            byte[] data = Files.readAllBytes(file);
            send(ex, 200, "application/octet-stream", data);
        } catch (Exception e) {
            send(ex, 404, "text/plain", "not found\n".getBytes());
        }
    }

    private static ObjectNode latestJson(int cameraId, Latest l) {
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
        cur.put("path", "/api/camera/" + cameraId + "/current.jpg");
        ObjectNode hm = root.putObject("heatmapU8");
        hm.put("width", l.heatmapU8Width());
        hm.put("height", l.heatmapU8Height());
        hm.put("path", "/api/camera/" + cameraId + "/heatmap.u8");
        return root;
    }

    private void handleClientApi(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            corsClientPreflight(ex);
            return;
        }
        if (!clientApi.enabled()) {
            sendClientJsonError(ex, 503, "client_api disabled");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.startsWith("/api/client/geometry-runtime")) {
            handleGeometryRuntime(ex);
            return;
        }
        if (!clientApi.kopcheniConfigured()) {
            sendClientJsonError(ex, 503, "client_api.kopcheni_base_url not set");
            return;
        }
        KopcheniHttpProxy.forward(ex, clientApi.kopcheniBaseUrl(), path);
    }

    private static void corsClientPreflight(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Accept");
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private void handleGeometryRuntime(HttpExchange ex) throws IOException {
        String m = ex.getRequestMethod();
        if (clientApi.geometryRuntime() == null) {
            sendClientJsonError(ex, 503, "geometry runtime not configured");
            return;
        }
        corsJson(ex);
        if ("GET".equalsIgnoreCase(m)) {
            ObjectNode root = JSON.createObjectNode();
            root.set("runtimeOverrides", JSON.valueToTree(clientApi.geometryRuntime().overridesCopy()));
            root.set(
                    "effectiveForNextGeometryInspect",
                    JSON.valueToTree(clientApi.geometryRuntime().effectiveForDisplay(clientApi.javaGeometryYaml()))
            );
            byte[] out = JSON.writeValueAsBytes(root);
            send(ex, 200, "application/json; charset=utf-8", out);
            return;
        }
        if ("PUT".equalsIgnoreCase(m)) {
            byte[] raw;
            try (InputStream in = ex.getRequestBody()) {
                raw = in.readAllBytes();
            }
            if (raw.length > 0) {
                Map<String, Object> body = JSON.readValue(raw, new TypeReference<>() {
                });
                clientApi.geometryRuntime().replaceAllFromClient(body);
            }
            byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            send(ex, 200, "application/json; charset=utf-8", ok);
            return;
        }
        if ("DELETE".equalsIgnoreCase(m)) {
            clientApi.geometryRuntime().clear();
            byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            send(ex, 200, "application/json; charset=utf-8", ok);
            return;
        }
        send(ex, 405, "text/plain", "method not allowed\n".getBytes(StandardCharsets.UTF_8));
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

    /**
     * Регистрирует путь к .u8 для opaque GET с префиксом {@code /api/heatmap-artifact/} и 32-символьным hex-токеном (WebSocket Фаза 4b).
     * Предыдущий токен для той же камеры перестаёт разрешаться.
     *
     * @return hex-токен (32 символа) или {@code null}
     */
    public String registerHeatmapArtifact(int cameraId, Path heatmapU8Path) {
        if (heatmapU8Path == null) {
            return null;
        }
        return heatmapArtifacts.register(cameraId, heatmapU8Path);
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
