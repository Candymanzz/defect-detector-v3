package com.example.iml.orchestrator.integration.stream;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Клиентский видеопоток: worker {@code start_stream} (continuous MVS) + {@code stream_poll} + JPEG на UI/WS.
 * На время стрима software trigger в worker выключен; инспекция блокируется через {@link #isStreaming(int)}.
 */
public final class CameraStreamService implements AutoCloseable {

    private static final long STREAM_POLL_INITIAL_DELAY_MS = 150L;

    private final Logger log;
    private final ClientStreamConfig cfg;
    private final Map<Integer, WorkerProcessSupervisor> workersByCamera;
    private final Map<String, Object> uiCfg;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<Integer, CameraStreamSession> sessions = new ConcurrentHashMap<>();
    private final MjpegStreamHub mjpegHub;
    private final ConcurrentHashMap<Integer, CameraStreamMetrics> metricsByCamera = new ConcurrentHashMap<>();
    private volatile WsOutboundMessenger outbound;

    public CameraStreamService(
            Logger log,
            ClientStreamConfig cfg,
            Map<Integer, WorkerProcessSupervisor> workersByCamera,
            Map<String, Object> uiCfg
    ) {
        this.log = log;
        this.cfg = cfg == null ? ClientStreamConfig.defaults() : cfg;
        this.workersByCamera = workersByCamera == null ? Map.of() : workersByCamera;
        this.uiCfg = uiCfg == null ? Map.of() : uiCfg;
        this.mjpegHub = new MjpegStreamHub();
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
        CameraStreamSession s = sessions.get(cameraId);
        return s != null && s.running.get();
    }

    public record StreamStartResult(int cameraId, int maxFps, String httpPath, String mjpegPath) {
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
        CameraStreamSession session = new CameraStreamSession(wsNotify, fps);
        metricsByCamera.putIfAbsent(cameraId, new CameraStreamMetrics());
        CameraStreamSession prev = sessions.putIfAbsent(cameraId, session);
        if (prev != null) {
            throw new IllegalStateException("stream already active for camera " + cameraId);
        }
        try {
            synchronized (worker) {
                worker.command(Map.of("op", "start_stream", "fps", fps));
            }
        } catch (RuntimeException e) {
            sessions.remove(cameraId);
            throw e;
        } catch (Exception e) {
            sessions.remove(cameraId);
            throw new StreamException("start_stream failed: " + e.getMessage(), e);
        }
        // Дать worker stream_thread время снять первый кадр (stream_poll иначе no_stream_frame_yet).
        session.future = scheduler.scheduleAtFixedRate(
                () -> CameraStreamPollSupport.pollAndPublish(
                        log, uiCfg, workersByCamera, sessions, metricsByCamera, mjpegHub, outbound, cameraId),
                STREAM_POLL_INITIAL_DELAY_MS,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
        log.info(
                "client_stream started camera={} fps={} interval_ms={} (ws notify after first frame)",
                cameraId, fps, intervalMs);
        return new StreamStartResult(cameraId, fps, httpPath, mjpegPath);
    }

    public void stop(int cameraId) {
        CameraStreamSession session = sessions.remove(cameraId);
        metricsByCamera.remove(cameraId);
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
}
