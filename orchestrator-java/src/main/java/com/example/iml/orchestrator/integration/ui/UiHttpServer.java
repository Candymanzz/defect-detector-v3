package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.http.HttpApplicationContext;
import com.example.iml.orchestrator.integration.http.HttpFrontController;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
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
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public record ClientPreviewArtifact(Path path, int width, int height) {
    }

    private final HttpServer httpServer;
    private final Map<Integer, Latest> latestByCamera = new ConcurrentHashMap<>();
    private final HeatmapArtifactRegistry heatmapArtifacts = new HeatmapArtifactRegistry();

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
        InetSocketAddress addr = new InetSocketAddress(host, port);
        this.httpServer = HttpServer.create(addr, 0);
        HttpApplicationContext appCtx = HttpApplicationContext.of(
                this,
                geometrySnapshotCache,
                clientApi == null ? ClientApiMount.disabled() : clientApi,
                lightClient,
                rootYaml == null ? Map.of() : rootYaml
        );
        HttpFrontController frontController = new HttpFrontController(appCtx);
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
