package com.example.iml.orchestrator.integration.stream;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Клиентский видеопоток: worker {@code start_stream} (continuous MVS) + {@code stream_poll} + JPEG на UI/WS.
 * На время стрима software trigger в worker выключен; инспекция блокируется через {@link #isStreaming(int)}.
 */
public final class CameraStreamService implements AutoCloseable {

    private static final long STREAM_POLL_INITIAL_DELAY_MS = 150L;

    private final Logger log;
    private final ClientStreamConfig cfg;
    private final Map<Integer, WorkerProcessSupervisor> workersByCamera;
    private final Map<Integer, String> productTypeByCamera;
    private final Map<Integer, String> detectorByCamera;
    private final UiHttpServer uiServer;
    private final ClientWebSocketServer clientWs;
    private final Map<String, Object> uiCfg;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<Integer, StreamSession> sessions = new ConcurrentHashMap<>();
    private final MjpegStreamHub mjpegHub;
    private volatile WsOutboundMessenger outbound;

    public CameraStreamService(
            Logger log,
            ClientStreamConfig cfg,
            Map<Integer, WorkerProcessSupervisor> workersByCamera,
            Map<Integer, String> productTypeByCamera,
            Map<Integer, String> detectorByCamera,
            UiHttpServer uiServer,
            ClientWebSocketServer clientWs,
            Map<String, Object> uiCfg
    ) {
        this.log = log;
        this.cfg = cfg == null ? ClientStreamConfig.defaults() : cfg;
        this.workersByCamera = workersByCamera == null ? Map.of() : workersByCamera;
        this.productTypeByCamera = productTypeByCamera == null ? Map.of() : productTypeByCamera;
        this.detectorByCamera = detectorByCamera == null ? Map.of() : detectorByCamera;
        this.uiServer = uiServer;
        this.clientWs = clientWs;
        this.uiCfg = uiCfg == null ? Map.of() : uiCfg;
        this.mjpegHub = new MjpegStreamHub(log);
        this.scheduler = Executors.newScheduledThreadPool(
                Math.max(1, this.workersByCamera.size()),
                r -> {
                    Thread t = new Thread(r, "client-stream");
                    t.setDaemon(true);
                    return t;
                }
        );
    }

    public void setOutbound(WsOutboundMessenger outbound) {
        this.outbound = outbound;
    }

    public MjpegStreamHub mjpegHub() {
        return mjpegHub;
    }

    public static String mjpegPath(int cameraId) {
        return MjpegStreamHub.mjpegPath(cameraId);
    }

    public boolean isStreaming(int cameraId) {
        StreamSession s = sessions.get(cameraId);
        return s != null && s.running.get();
    }

    public record StreamStartResult(int cameraId, int maxFps, String httpPath, String mjpegPath) {
    }

    public record StreamStatus(int cameraId, boolean active, int maxFps, String httpPath, String mjpegPath) {
    }

    /** @deprecated use {@link #start(int, int, WebSocket)} */
    public void start(WebSocket connection, int cameraId, int requestedFps) {
        start(cameraId, requestedFps, connection);
    }

    /**
     * Запуск видеопотока. {@code wsNotify} — для {@code server.stream_started}; может быть {@code null} (HTTP).
     */
    public StreamStartResult start(int cameraId, int requestedFps, WebSocket wsNotify) {
        WorkerProcessSupervisor worker = workersByCamera.get(cameraId);
        if (worker == null) {
            throw new IllegalArgumentException("unknown camera_id: " + cameraId);
        }
        if (sessions.containsKey(cameraId)) {
            throw new IllegalStateException("stream already active for camera " + cameraId);
        }
        int fps = cfg.clampFps(requestedFps);
        long intervalMs = Math.max(1, 1000L / fps);
        String httpPath = "/api/camera/" + cameraId + "/current.jpg";
        String mjpegPath = mjpegPath(cameraId);
        StreamSession session = new StreamSession(wsNotify, fps);
        StreamSession prev = sessions.putIfAbsent(cameraId, session);
        if (prev != null) {
            throw new IllegalStateException("stream already active for camera " + cameraId);
        }
        try {
            synchronized (worker) {
                worker.command(Map.of("op", "start_stream", "fps", fps));
            }
        } catch (Exception e) {
            sessions.remove(cameraId);
            throw new RuntimeException("start_stream failed: " + e.getMessage(), e);
        }
        // Дать worker stream_thread время снять первый кадр (stream_poll иначе no_stream_frame_yet).
        session.future = scheduler.scheduleAtFixedRate(
                () -> pollAndPublish(cameraId),
                STREAM_POLL_INITIAL_DELAY_MS,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
        log.info("client_stream started camera={} fps={} interval_ms={}", cameraId, fps, intervalMs);
        if (outbound != null && wsNotify != null && wsNotify.isOpen()) {
            outbound.sendStreamStarted(wsNotify, cameraId, fps, httpPath, mjpegPath);
        }
        return new StreamStartResult(cameraId, fps, httpPath, mjpegPath);
    }

    public StreamStatus status(int cameraId) {
        StreamSession session = sessions.get(cameraId);
        boolean active = session != null && session.running.get();
        int fps = active ? session.fps : 0;
        String httpPath = "/api/camera/" + cameraId + "/current.jpg";
        return new StreamStatus(cameraId, active, fps, httpPath, mjpegPath(cameraId));
    }

    public void stop(int cameraId) {
        StreamSession session = sessions.remove(cameraId);
        if (session == null) {
            return;
        }
        session.running.set(false);
        if (session.future != null) {
            session.future.cancel(false);
        }
        WorkerProcessSupervisor worker = workersByCamera.get(cameraId);
        if (worker != null) {
            try {
                synchronized (worker) {
                    worker.command(Map.of("op", "stop_stream"));
                }
            } catch (Exception e) {
                log.warn("stop_stream camera={}: {}", cameraId, e.getMessage());
            }
        }
        mjpegHub.closeCamera(cameraId);
        log.info("client_stream stopped camera={}", cameraId);
        if (outbound != null && session.connection != null && session.connection.isOpen()) {
            outbound.sendStreamStopped(session.connection, cameraId);
        }
    }

    public void stopAll() {
        for (Integer cameraId : sessions.keySet().toArray(Integer[]::new)) {
            stop(cameraId);
        }
    }

    private void pollAndPublish(int cameraId) {
        StreamSession session = sessions.get(cameraId);
        if (session == null || !session.running.get()) {
            return;
        }
        if (!session.tickInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            WorkerProcessSupervisor worker = workersByCamera.get(cameraId);
            if (worker == null) {
                return;
            }
            BinaryProtocol.Message capture;
            synchronized (worker) {
                capture = worker.command(Map.of("op", "stream_poll"));
            }
            if (capture == null) {
                return;
            }
            if (capture.type() == BinaryProtocol.MSG_ERROR) {
                session.notePollError(log, cameraId, formatWorkerError(capture));
                return;
            }
            if (capture.header() == null) {
                return;
            }
            Map<String, Object> header = capture.header();
            session.pollErrors.set(0);
            long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
            if (frameId < 0) {
                return;
            }
            String shmName = String.valueOf(header.get("shm_name"));
            int width = YamlScalars.toInt(header.get("width"), 0);
            int height = YamlScalars.toInt(header.get("height"), 0);
            int stride = YamlScalars.toInt(header.get("stride"), 0);
            if (shmName.isBlank() || width <= 0 || height <= 0) {
                return;
            }

            String productType = productTypeByCamera.getOrDefault(cameraId, "camera-" + cameraId);
            String detectorId = detectorByCamera.getOrDefault(cameraId, "v1");
            PathHolder jpeg = writePreviewJpeg(cameraId, shmName, width, height, stride);
            if (jpeg.path != null && Files.isRegularFile(jpeg.path)) {
                uiServer.update(
                        cameraId,
                        frameId,
                        productType,
                        detectorId,
                        shmName,
                        width,
                        height,
                        jpeg.path,
                        jpeg.width,
                        jpeg.height,
                        null,
                        0,
                        0
                );
                try {
                    mjpegHub.publish(cameraId, Files.readAllBytes(jpeg.path));
                } catch (IOException e) {
                    log.debug("client_stream mjpeg publish camera={}: {}", cameraId, e.getMessage());
                }
            }
            String httpPath = "/api/camera/" + cameraId + "/current.jpg";
            if (clientWs != null) {
                clientWs.notifyPreviewFrame(cameraId, productType, detectorId, header, httpPath);
            }
        } catch (Exception e) {
            session.notePollError(log, cameraId, e.getMessage());
        } finally {
            session.tickInProgress.set(false);
        }
    }

    private static String formatWorkerError(BinaryProtocol.Message capture) {
        if (capture.payload() != null && capture.payload().length > 0) {
            try {
                return new String(capture.payload(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return capture.header() == null ? "worker_error" : String.valueOf(capture.header());
    }

    private PathHolder writePreviewJpeg(int cameraId, String shmName, int width, int height, int stride) {
        int previewMaxW = YamlScalars.toInt(uiCfg.get("client_preview_max_width"), 0);
        int qualPct = YamlScalars.toInt(uiCfg.get("client_preview_jpeg_quality"), 58);
        qualPct = Math.min(100, Math.max(5, qualPct));
        float q = qualPct / 100f;
        UiHttpServer.ClientPreviewArtifact art = UiHttpServer.writeCurrentJpegFromBgrShm(
                shmName, width, height, stride, previewMaxW, q, cameraId);
        return new PathHolder(art.path(), art.width(), art.height());
    }

    @Override
    public void close() {
        stopAll();
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    private static final class StreamSession {
        private static final int POLL_ERROR_LOG_EVERY = 20;

        final WebSocket connection;
        final int fps;
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicBoolean tickInProgress = new AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicInteger pollErrors = new java.util.concurrent.atomic.AtomicInteger();
        volatile ScheduledFuture<?> future;

        StreamSession(WebSocket connection, int fps) {
            this.connection = connection;
            this.fps = fps;
        }

        void notePollError(Logger log, int cameraId, String reason) {
            int n = pollErrors.incrementAndGet();
            if (n == 1 || n % POLL_ERROR_LOG_EVERY == 0) {
                log.warn("client_stream poll camera={} fail #{}: {}", cameraId, n, reason);
            }
        }
    }

    private record PathHolder(Path path, int width, int height) {
    }
}
