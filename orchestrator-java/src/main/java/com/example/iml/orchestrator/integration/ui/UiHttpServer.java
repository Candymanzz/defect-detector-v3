package com.example.iml.orchestrator.integration.ui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.http.HttpApplicationContext;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.http.HttpFrontController;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.openapi.OrchestratorApiDocumentationHandlers;
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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Локальный HTTP для превью current/heatmap и (при наличии {@link GeometrySnapshotCache}) geometry.
 * Маршрутизация — {@link HttpFrontController} (паттерн Front Controller).
 */
public final class UiHttpServer implements AutoCloseable, CameraPreviewStore {
    private static final Logger LOG = LogManager.getLogger(UiHttpServer.class);
    private static final Path PREVIEW_OUTPUT_DIR = Path.of(
            System.getProperty("java.io.tmpdir"),
            "iml-ui-current"
    );
    private static final Path INSPECTION_ARTIFACT_DIR = Path.of(
            System.getProperty("java.io.tmpdir"),
            "iml-ui-inspection-artifacts"
    );

    public record ClientPreviewArtifact(Path path, int width, int height, String error) {
        public static ClientPreviewArtifact ok(Path path, int width, int height) {
            return new ClientPreviewArtifact(path, width, height, null);
        }

        public static ClientPreviewArtifact failed(String error) {
            return new ClientPreviewArtifact(null, 0, 0, error);
        }
    }

    public record InspectionPreviewArtifacts(
            ClientPreviewArtifact frame,
            ClientPreviewArtifact card
    ) {
    }

    private final HttpServer httpServer;
    private final HttpApplicationContext httpContext;
    private final Map<Integer, Latest> latestByCamera = new ConcurrentHashMap<>();
    private final HeatmapArtifactRegistry heatmapArtifacts = new HeatmapArtifactRegistry();
    private final InspectionArtifactRegistry inspectionArtifacts = new InspectionArtifactRegistry(INSPECTION_ARTIFACT_DIR);

    public UiHttpServer(String host, int port) throws IOException {
        this(host, port, null, ClientApiMount.disabled(), null, Map.of());
    }

    public UiHttpServer(String host, int port, GeometrySnapshotCache geometrySnapshotCache) throws IOException {
        this(host, port, geometrySnapshotCache, ClientApiMount.disabled(), null, Map.of());
    }

    public UiHttpServer(String host, int port, GeometrySnapshotCache geometrySnapshotCache, ClientApiMount clientApi)
            throws IOException {
        this(host, port, geometrySnapshotCache, clientApi, null, Map.of());
    }

    public UiHttpServer(
            String host,
            int port,
            GeometrySnapshotCache geometrySnapshotCache,
            ClientApiMount clientApi,
            LightTriggerClient lightClient,
            Map<String, Object> rootYaml
    ) throws IOException {
        this(host, port, geometrySnapshotCache, clientApi, lightClient, rootYaml, null);
    }

    public UiHttpServer(
            String host,
            int port,
            GeometrySnapshotCache geometrySnapshotCache,
            ClientApiMount clientApi,
            LightTriggerClient lightClient,
            Map<String, Object> rootYaml,
            CameraSettingsStore cameraSettingsStore
    ) throws IOException {
        this(host, port, geometrySnapshotCache, clientApi, lightClient, rootYaml, cameraSettingsStore, null);
    }

    public UiHttpServer(
            String host,
            int port,
            GeometrySnapshotCache geometrySnapshotCache,
            ClientApiMount clientApi,
            LightTriggerClient lightClient,
            Map<String, Object> rootYaml,
            CameraSettingsStore cameraSettingsStore,
            LightBrightnessStore lightBrightnessStore
    ) throws IOException {
        InetSocketAddress addr = new InetSocketAddress(host, port);
        this.httpServer = HttpServer.create(addr, 0);
        this.httpContext = HttpApplicationContext.of(
                this,
                geometrySnapshotCache,
                clientApi == null ? ClientApiMount.disabled() : clientApi,
                lightClient,
                rootYaml == null ? Map.of() : rootYaml,
                cameraSettingsStore,
                lightBrightnessStore
        );
        HttpFrontController frontController = new HttpFrontController(httpContext);
        OrchestratorApiDocumentationHandlers.register(httpServer);
        httpServer.createContext("/", exchange -> frontController.dispatch(exchange));
        httpServer.setExecutor(null);
        httpServer.start();
    }

    @Override
    public Optional<Latest> latest(int cameraId) {
        return Optional.ofNullable(latestByCamera.get(cameraId));
    }

    @Override
    public Map<Integer, Latest> latestByCamera() {
        return Map.copyOf(latestByCamera);
    }

    @Override
    public String registerHeatmapArtifact(int cameraId, Path heatmapU8Path) {
        if (heatmapU8Path == null) {
            return null;
        }
        return heatmapArtifacts.register(cameraId, heatmapU8Path);
    }

    @Override
    public Path resolveHeatmapArtifactPath(String token) {
        return heatmapArtifacts.resolve(token);
    }

    @Override
    public RegisteredInspectionArtifacts registerInspectionArtifacts(
            int cameraId,
            long frameId,
            Path frameJpeg,
            Path cardJpeg,
            Path heatmapU8
    ) throws IOException {
        InspectionArtifactRegistry.Bundle bundle =
                inspectionArtifacts.register(cameraId, frameId, frameJpeg, cardJpeg, heatmapU8);
        return new RegisteredInspectionArtifacts(
                bundle.id(),
                bundle.frameJpeg(),
                bundle.cardJpeg(),
                bundle.heatmapU8()
        );
    }

    public RegisteredInspectionArtifacts attachInspectionHeatmap(String bundleId, Path heatmapU8) throws IOException {
        InspectionArtifactRegistry.Bundle bundle = inspectionArtifacts.attachHeatmap(bundleId, heatmapU8);
        return new RegisteredInspectionArtifacts(
                bundle.id(),
                bundle.frameJpeg(),
                bundle.cardJpeg(),
                bundle.heatmapU8()
        );
    }

    @Override
    public byte[] readInspectionArtifact(String bundleId, String artifactName) throws IOException {
        return inspectionArtifacts.read(bundleId, artifactName);
    }

    @Override
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
            int heatmapU8H,
            InspectionDecision decision
    ) {
        Boolean overallPass = null;
        String action = null;
        Double anomalyScore = null;
        String pythonStatus = null;
        String geometryStatus = null;
        if (decision != null) {
            overallPass = decision.overallPass();
            action = decision.action();
            anomalyScore = decision.anomalyScore();
            pythonStatus = decision.pythonStatus();
            geometryStatus = decision.geometryStatus();
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
                        overallPass,
                        action,
                        anomalyScore,
                        pythonStatus,
                        geometryStatus,
                        System.currentTimeMillis()
                )
        );
    }

    public void attachCameraStreamService(CameraStreamService cameraStreamService) {
        if (httpContext != null && httpContext.cameraStreamHolder() != null) {
            httpContext.cameraStreamHolder().set(cameraStreamService);
        }
    }

    public void attachCameraWorkers(java.util.Map<Integer, WorkerProcessSupervisor> workersByCamera) {
        if (httpContext != null && httpContext.cameraWorkersHolder() != null) {
            httpContext.cameraWorkersHolder().set(workersByCamera);
        }
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
        return writeCurrentJpegFromBgrShm(shmName, width, height, stride, 0L, previewMaxWidth, quality, -1);
    }

    public static ClientPreviewArtifact writeCurrentJpegFromBgrShm(
            String shmName, int width, int height, int stride, int previewMaxWidth, float quality, int cameraId
    ) {
        return writeCurrentJpegFromBgrShm(shmName, width, height, stride, 0L, previewMaxWidth, quality, cameraId);
    }

    public static ClientPreviewArtifact writeCurrentJpegFromBgrShm(
            String shmName,
            int width,
            int height,
            int stride,
            long shmOffset,
            int previewMaxWidth,
            float quality,
            int cameraId
    ) {
        BufferedImage source;
        try {
            source = readBgrImageFromShm(shmName, width, height, stride, shmOffset, cameraId);
        } catch (Exception e) {
            return previewJpegFailed(e.getMessage());
        }
        return writePreviewJpeg(source, previewMaxWidth, quality, cameraId);
    }

    public static InspectionPreviewArtifacts writeInspectionJpegsFromBgrShm(
            String shmName,
            int width,
            int height,
            int stride,
            long shmOffset,
            int frameMaxWidth,
            float frameQuality,
            int cardMaxWidth,
            float cardQuality
    ) {
        final BufferedImage source;
        try {
            source = readBgrImageFromShm(shmName, width, height, stride, shmOffset, -1);
        } catch (Exception e) {
            ClientPreviewArtifact failed = previewJpegFailed(e.getMessage());
            return new InspectionPreviewArtifacts(failed, failed);
        }

        return new InspectionPreviewArtifacts(
                writePreviewJpeg(source, frameMaxWidth, frameQuality, -1),
                writePreviewJpeg(source, cardMaxWidth, cardQuality, -1)
        );
    }

    private static BufferedImage readBgrImageFromShm(
            String shmName,
            int width,
            int height,
            int stride,
            long shmOffset,
            int cameraId
    ) throws IOException {
        if (width <= 0 || height <= 0 || stride < width * 3 || shmOffset < 0) {
            throw new IOException(
                    "invalid frame geometry width=" + width + " height=" + height + " stride=" + stride
                            + " shmOffset=" + shmOffset
            );
        }
        Path shmPath = FrameJpegWriter.resolveShmPath(shmName, cameraId);
        if (shmPath == null || !Files.isRegularFile(shmPath)) {
            throw new IOException("shm not readable shmName=" + shmName + " path=" + shmPath);
        }
        long need = (long) stride * (long) height;
        try (FileChannel ch = FileChannel.open(shmPath, StandardOpenOption.READ)) {
            long fileSize = Math.max(0, ch.size());
            if (fileSize < shmOffset + need || need < (long) width * 3L * height) {
                throw new IOException(
                        "shm size mismatch fileSize=" + fileSize + " need=" + need + " shmOffset=" + shmOffset
                );
            }
            // Avoid FileChannel.map here: on Windows a mapped section keeps the SHM file locked and
            // breaks the next freeze/JPEG write into iml_ui_inspect_cam_*.
            byte[] raw = new byte[Math.toIntExact(need)];
            ByteBuffer readBuf = ByteBuffer.wrap(raw);
            int totalRead = 0;
            while (totalRead < need) {
                int read = ch.read(readBuf, shmOffset + totalRead);
                if (read <= 0) {
                    throw new IOException("shm read incomplete at offset=" + (shmOffset + totalRead));
                }
                totalRead += read;
            }
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            byte[] dst = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < height; y++) {
                System.arraycopy(raw, y * stride, dst, y * width * 3, width * 3);
            }
            return img;
        }
    }

    private static ClientPreviewArtifact writePreviewJpeg(
            BufferedImage source,
            int previewMaxWidth,
            float quality,
            int cameraId
    ) {
        int outW = source.getWidth();
        int outH = source.getHeight();
        BufferedImage output = source;
        if (previewMaxWidth > 0 && outW > previewMaxWidth) {
            outW = previewMaxWidth;
            outH = Math.max(1, (int) Math.round((double) source.getHeight() * previewMaxWidth / source.getWidth()));
            output = new BufferedImage(outW, outH, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D graphics = output.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, 0, 0, outW, outH, null);
            } finally {
                graphics.dispose();
            }
        }
        try {
            Path out = cameraId >= 0
                    ? writeStablePreviewJpeg(cameraId, output, quality)
                    : writeTempPreviewJpeg(output, quality);
            if (out == null) {
                return previewJpegFailed("jpeg encode failed cameraId=" + cameraId);
            }
            return ClientPreviewArtifact.ok(out, outW, outH);
        } catch (Exception e) {
            return previewJpegFailed("exception: " + e.getMessage());
        }
    }

    private static ClientPreviewArtifact previewJpegFailed(String reason) {
        LOG.debug("preview jpeg failed: {}", reason);
        return ClientPreviewArtifact.failed(reason);
    }

    private static Path writeTempPreviewJpeg(BufferedImage image, float quality) throws IOException {
        Path out = Files.createTempFile("iml-ui-current-", ".jpg");
        if (!encodeJpeg(image, out, quality)) {
            try {
                Files.deleteIfExists(out);
            } catch (IOException ignored) {
                // best effort
            }
            return null;
        }
        return out;
    }

    private static Path writeStablePreviewJpeg(int cameraId, BufferedImage image, float quality) throws IOException {
        Files.createDirectories(PREVIEW_OUTPUT_DIR);
        Path target = PREVIEW_OUTPUT_DIR.resolve("camera-" + cameraId + "-current.jpg");
        Path tmp = Files.createTempFile(PREVIEW_OUTPUT_DIR, "camera-" + cameraId + "-", ".jpg.tmp");
        boolean encoded = false;
        try {
            encoded = encodeJpeg(image, tmp, quality);
            if (!encoded) {
                return null;
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return target;
            } catch (IOException e) {
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    return target;
                } catch (IOException moveFail) {
                    // Windows can transiently lock target while frontend reads it.
                    // Fallback to direct rewrite to keep preview frames flowing.
                    if (encodeJpeg(image, target, quality)) {
                        return target;
                    }
                    return null;
                }
            }
        } finally {
            if (!encoded || Files.exists(tmp)) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static boolean encodeJpeg(BufferedImage image, Path out, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            return false;
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
            writer.write(null, new IIOImage(image, null, null), p);
            return true;
        } finally {
            writer.dispose();
        }
    }
}
